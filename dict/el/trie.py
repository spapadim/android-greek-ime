#! /usr/bin/env python

import sys
import getopt
import time
import codecs
import struct

logfp = sys.stderr

# Byte lengths in binary encoding
_COUNT_SIZE = 1
_ADDR_SIZE  = 3   # non-null address
_NULL_SIZE  = 1   # null address
_FREQ_SIZE  = 1 

_ADDRESS_MASK       = 0x3FFFFF
_FLAG_ADDRESS_MASK  = 0x40
_FLAG_TERMINAL_MASK = 0x80
  
class trie:
    """Implementation of a dictionary trie.
    Child entries are [freq, subtrie]"""
    def __init__ (self):
        self.children = {}
    
    def insert (self, key, freq):
        if len(key) == 0:
            return
        head, tail = key[0], key[1:]
        # Make sure there is a child entry, whether it has a node or not
        if head not in self.children:
            self.children[head] = [None, None]
        if len(tail) > 0:
            # Mak sure there is a child node
            if self.children[head][1] is None:
                self.children[head][1] = trie()
            self.children[head][1].insert(tail, freq)
        else:
            self.children[head][0] = freq

    def _codeSize (self, encoding):
        assert len(self.children) > 0
        sz = _COUNT_SIZE
        for c in sorted(self.children):
            freq, subtrie = self.children[c]
            try:
                c.encode(encoding)
                sz += 1
            except UnicodeEncodeError:
                sz += 3
            if subtrie is not None:
                sz += _ADDR_SIZE
            else:
                sz += _NULL_SIZE
            if freq is not None:
                sz += _FREQ_SIZE
        return sz
    
    def _assignAddresses (self, encoding, start = 0):
        startTime = time.time()
        q = []  # queue for breadth-first search
        addr = start
        q.append(self)
        while len(q) > 0:
            trie = q.pop(0)
            trie.addr = addr
            addr += trie._codeSize(encoding)
            for c in sorted(trie.children):
                freq, subtrie = trie.children[c]
                if subtrie is not None:
                    q.append(subtrie)
        print >>logfp, "Assigned addresses in %.1f" % (time.time() - startTime)

    def dump (self, fp, encoding = 'iso8859-7'):
        self._assignAddresses(encoding)
        startTime = time.time()
        q = [] # queue for breadth-first search
        q.append(self)
        while len(q) > 0:
            trie = q.pop(0)
            fp.write(chr(len(trie.children)))  # write no. of children
            for c in sorted(trie.children):
                try:
                    fp.write(c.encode(encoding))
                except UnicodeEncodeError:
                    fp.write(struct.pack('!BH', 0xFF, ord(c)))
                freq, subtrie = trie.children[c]
                if subtrie is not None:
                    # Add it to be visited
                    q.append(subtrie)
                    # Create address
                    assert(subtrie.addr & ~_ADDRESS_MASK == 0)
                    addr = subtrie.addr & _ADDRESS_MASK
                    addr |= _FLAG_ADDRESS_MASK << 16
                    if freq is not None:
                        addr |= _FLAG_TERMINAL_MASK << 16
                    # Write address
                    fp.write(struct.pack('!BBB', addr >> 16, (addr >> 8) & 0xFF, addr & 0xFF))
                    # Write frequency, if also terminal
                    if freq is not None:
                        fp.write(struct.pack('!B', freq))
                else:  # subtrie is None
                    assert(freq is not None)
                    fp.write(struct.pack('!BB', _FLAG_TERMINAL_MASK, freq))
        print >>logfp, 'Dumped trie in %.1f sec' % (time.time() - startTime)

def loadDict (fp):
    startTime = time.time()
    t = trie()
    count = skipCount = 0
    for l in fp:
        word, freq = l.strip().split()
        if freq > 0:
            t.insert(word, int(freq))
        else:
            skipCount += 1
        count += 1
    print >>logfp, 'Processed %d words (%d ignored) in %.1f sec' % \
        (count, skipCount, time.time() - startTime)
    return t

def printUsageAndExit ():
    print >>sys.stderr, "Usage: %s infile outfile" % sys.argv[0]
    sys.exit(0)

def main (argv):
    if len(sys.argv) != 3:
        printUsageAndExit()
    
    # Load dictionary into trie
    fp = codecs.open(sys.argv[1], 'r', 'utf8')
    t = loadDict(fp)
    fp.close()
    
    # Dump it in binary format
    fp = open(sys.argv[2], 'w')
    t.dump(fp)
    fp.close()

if __name__ == '__main__':
    main(sys.argv[1:])
