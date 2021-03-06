#! /usr/bin/env python

import sys
import getopt
import time
import math
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
    
    def insert (self, key, freq, noExpand = False):
        assert len(key) > 0
        head, tail = key[0], key[1:]
        # Make sure there is a child entry, whether it has a node or not
        if head not in self.children:
            # If noExpand is true, word won't be inserted
            # if the tree needs to grow in any way
            if noExpand:
                return False
            else:
                self.children[head] = [None, None]
        if len(tail) > 0:
            # Make sure there is a child node
            if self.children[head][1] is None:
                childTrie = trie()
            else:
                childTrie = self.children[head][1]
            inserted = childTrie.insert(tail, freq, noExpand)
            if inserted:
                self.children[head][1] = childTrie
            return inserted
        else:
            self.children[head][0] = freq
            return True
        
    def prettyPrint (self, fp = sys.stdout, level = 0):
        padStr = '  ' * level;
        for c in sorted(self.children):
            f, n = self.children[c]
            if f is None:
                print >>fp, '%s%s *' % (padStr, c)
            else:
                print >>fp, '%s%s %d' % (padStr, c, f)
            if n is not None:
                n.prettyPrint(fp, level+1)

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

def identity (x):
    return x

def logPlus1 (x):
    return math.log(x + 1)

_progress_interval = 100

def loadDict (fp, t, thresh = 0, scale = 255, transform = identity, noExpand = False):
    print >>logfp, 'Using raw frequency threshold of %d, noExpand is %s' % (thresh, str(noExpand))
    startTime = time.time()
    count = skipCount = 0
    print >>logfp, 'Loading: %6d words (%6d skipped)' % (count, skipCount),
    scaleFactor = None
    for l in fp:
        word, freq, rawFreq = l.strip().split()[0:3]
        freq = int(freq)  # ignored
        rawFreq = int(rawFreq)
        if scaleFactor is None:
            # Assumes input is in descending frequency order
            scaleFactor = float(scale)/float(transform(rawFreq))
        freq = int(round(transform(rawFreq)*scaleFactor))
        if freq == 0:
            # zero-frequency entries are never suggested
            # and just waste space, so bump up 
            freq = 1
        inserted = False
        if rawFreq > thresh or noExpand:
            inserted = t.insert(word, freq, noExpand)
        if not inserted:
            skipCount += 1
        count += 1
        if count % _progress_interval == 0:
            print >>logfp, '\b'*31,
            print >>logfp, '%6d words (%6d skipped)' % (count, skipCount),
    print >>logfp, '\nProcessed %d words (%d ignored, %d kept) in %.1f sec' % \
        (count, skipCount, count - skipCount, time.time() - startTime)
    return t

_default_thresh = 0
_default_scale = 255
_default_xform = identity

def printUsageAndExit ():
    print >>sys.stderr, 'Usage: %s [options] infile outfile' % sys.argv[0]
    print >>sys.stderr, ' -t|-thresh  Raw frequency threshold for pruning'
    print >>sys.stderr, '               (default: %d)' % _default_thresh
    print >>sys.stderr, ' -1|-onepass Make just one pass'
    print >>sys.stderr, ' -2|-twopass Make second pass to insert free words; default'
    print >>sys.stderr, ' --log       Logarithmic transformation'
    print >>sys.stderr, ' --sqrt      Square root transformation'
    print >>sys.stderr, ' --lin       Linear scaling (no transformation); default'
    print >>sys.stderr, ' -s|-scale   Scale to this maximum value'
    print >>sys.stderr, '               (default: %d)' % _default_scale
    sys.exit(0)

def main (argv):
    opts, args = getopt.getopt(sys.argv[1:], 'h12t:s:', 
                               ['help', 'onepass', 'twopass', 'thresh=', 'scale=', 'log', 'sqrt', 'lin'])
    thresh = _default_thresh
    scale = _default_scale
    xform = _default_xform
    twoPass = True
    for opt, arg in opts:
        if opt == '-h' or opt == '--help':
            printUsageAndExit()
        elif opt == '-1' or opt == '--onepass':
            twoPass = False
        elif opt == '-2' or opt == '--twopass':
            twoPass = True
        elif opt == '-t' or opt == '--thresh':
            thresh = int(arg)
        elif opt == '-s' or opt == '--scale':
            scale = int(arg)
        elif opt == '--log':
            xform = logPlus1
        elif opt == '--sqrt':
            xform = math.sqrt
        elif opt == '--lin':
            xform = identity
        else:
            print >>sys.stderr, 'Invalid option:', opt
            printUsageAndExit()

    if len(args) != 2:
        print >>sys.stderr, 'Invalid number of arguments; must specify exactly two filenames'
        printUsageAndExit()
        
    inFilename = args[0]
    outFilename = args[1]
    
    # Load dictionary into trie
    print >>logfp, 'First pass (initial trie build)'
    fp = codecs.open(inFilename, 'r', 'utf8')
    t = loadDict(fp, trie(), thresh, scale, xform)
    fp.close()
    
    # Make second pass and add words that can be inserted without growing trie
    if twoPass:
        print >>logfp, 'Second pass (insert words without growing trie)'
        fp = codecs.open(inFilename, 'r', 'utf8')
        t = loadDict(fp, t, thresh, scale, xform, noExpand=True)
        fp.close()
    
    # Dump it in binary format
    fp = open(outFilename, 'w')
    t.dump(fp)
    fp.close()

if __name__ == '__main__':
    main(sys.argv[1:])
