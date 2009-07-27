import sys
import bz2
import xml.sax
import re
import getopt
import codecs
import time

logfp = sys.stderr

_wikilink_re = re.compile(r'\[\[(:?(?:[^:|\]]+:)+)?([^|\]]+\|)?([^\]]*)\]\]') # FIXME multi-colon
_wikimacro_re = re.compile(r'{{[^}]+}}')
_html_re = re.compile(r'</?[^>]+>|&[^;]+;|<!--.+?-->')
_ignore_chars_re = re.compile(r'[a-zA-Z0-9.,;:_?!\'"(){}\[\]|/#&%|~+=$*@<>\-]')

# Map from accented to unaccented characters; lowercase only
_accent_table = {0x03ac : 0x03b1,  # alpha acute
                 0x03ad : 0x03b5,  # epsilon acute
                 0x03ae : 0x03b7,  # eta acute
                 0x03af : 0x03b9,  # iota acute
                 0x03cc : 0x03bf,  # omicron acute
                 0x03cd : 0x03c5,  # upsilon acute
                 0x03cf : 0x03c9,  # omega acute
                 0x03ca : 0x03b9,  # iota diaeresis
                 0x03cb : 0x03c5,  # upsilon diaeresis
                 0x0390 : 0x03b9,  # iota acute diaeresis
                 0x03b0 : 0x03c5 } # upsilon acute diaeresis

def greekNormalize (s):
    """Return normalized representation: all-lowercase with no accents"""
    return s.lower().translate(_accent_table)

_progress_interval = 500  # Update progress message every so many word

class GreekWordHistogram:
    """Stores histogram of Greek words.  Keys are normalized representations,
    values are two-element lists.  First element is a set of non-normalized
    representations and second element is aggregate count for all of them."""
    def __init__ (self):
        self.hist = {}
    
    def __len__ (self):
        return len(self.hist)

    def __getitem__ (self, key):
        """Return corresponding count of normalized representation"""
        return self.hist[greekNormalize(key)][1]
    
    def get (self, key, default=None):
        normKey = greekNormalize(key)
        if self.hist.has_key(normKey):
            return self.hist[normKey][1]
        else:
            return default
    
    def increment (self, key):
        normKey = greekNormalize(key)
        if not self.hist.has_key(normKey):
            self.hist[normKey] = [set(), 0]
        data = self.hist[normKey]
        data[0].add(key)
        data[1] += 1
    
    def __iter__ (self):
        return self.hist.iterkeys()
    
    iterkeys = __iter__

    def __contains__ (self, key):
        return self.hist.has_key(greekNormalize(key))
    
    def dump (self, fp):
        startTime = time.time()
        for normKey in self.hist:
            rawKeys, count = self.hist[normKey]
            print >>fp, '%s\t%d\t%s' % (normKey, count, '\t'.join(rawKeys))
        print >>logfp, "Dumped %d words from Wikipedia in %.1f sec" % (len(self.hist), time.time() - startTime)

def _wikiReplace (match):
    namespace = match.group(1)
    if namespace > 0: # and namespace[0] == ':' and namespace[-1] == ':':
        return ''  # Ignore cross-wiki links
    else:
        return match.group(3) or match.group(2) or ''  # XXX use group-2 if group-3 is empty?

def stripWikiMarkup (text):
    if text.startswith('#REDIRECT'):
        return ''  # Do nothing
    # Remove all HTML tags, entities and comments
    text = _html_re.sub('', text)
    # Strip all wiki markup
    text = _wikimacro_re.sub('', text)
    text = _wikilink_re.sub(_wikiReplace, text)
    # Strip English characters, numbers and punctuation
    text = _ignore_chars_re.sub(' ', text)
    return text    

class StopParsingException (Exception):
    pass

class WikiHandler (xml.sax.ContentHandler):
    def __init__ (self, hist):
        self.hist = hist
        self.cdata = ''
        self.inTextElement = False
        self.count = 0
        
    def startDocument (self):
        print >>logfp, "Parsing:         0 articles",
    
    def endDocument (self):
        print >>logfp, "\n"

    def startElement (self, name, attrs):
        if name != 'text':
            return
        self.inTextElement = True
    
    def endElement (self, name):
        if name != 'text':
            return
        self.inTextElement = False
        self.count += 1
        if self.count % _progress_interval == 0:
            print >>logfp, "\b"*20,
            print >>logfp, "%9d articles" % self.count,
        if self.count > 1000:  # TODO
            raise StopParsingException()
        for word in stripWikiMarkup(self.cdata).split():
            self.hist.increment(word)
        self.cdata = ''
    
    def characters (self, content):
        if self.inTextElement:
            self.cdata += content

_default_wiki_filename = 'elwiki-20090712-pages-articles.xml.bz2'
_default_dict_filename = 'el-utf8.txt'
_default_dump_filename = 'wiki_hist.txt'
_default_out_filename = 'words_hist.txt'

def printUsageAndExit ():
    print >>sys.stderr, 'Usage: %s [-w|--wiki file] [-d|--dict file]' % sys.argv[0]
    print >>sys.stderr, '  -w|--wiki  Wikipedia article compressed dump'
    print >>sys.stderr, '               (default: %s)' % _default_wiki_filename
    print >>sys.stderr, '  -d|--dict  Unix dictionary list (UTF textfile)'
    print >>sys.stderr, '               (default: %s)' % _default_dict_filename
    print >>sys.stderr, '  -u|--dump  Wikipedia histogram dump filename'
    print >>sys.stderr, '               (default: %s)' % _default_dump_filename
    print >>sys.stderr, '  -o|--out   Dictinary histogram output filename'
    print >>sys.stderr, '               (default: %s)' % _default_out_filename
    print >>sys.stderr, '  -h|--help  Print this usage information and exit'  
    sys.exit(0)

def parseWikipediaDump (fp):
    startTime = time.time()
    hist = GreekWordHistogram()
    handler = WikiHandler(hist)
    try:
        xml.sax.parse(fp, handler)
    except StopParsingException:
        pass
    print >>logfp, "Processed %d articles in %.1f sec" % (handler.count, time.time() - startTime)
    return hist

def loadDict (fp, default=0):
    """Load a Unix-style dictionary (one word per line) into a dictionary.
    Words are dictionary keys. Values are all initialized to the default"""
    startTime = time.time()
    dict = {}
    for l in fp:
        dict[l.strip()] = default
    print >>logfp, "Loaded %d words in %.1f sec" % (len(dict), time.time() - startTime)
    return dict

def crossReference (dict, hist):
    """Augment histogram with word frequencies, by cross-referencing with Wikipedia histogram"""
    startTime = time.time()
    for word in dict:
        dict[word] = hist.get(word, 0)
    print >>logfp, "Cross-referenced %d words in %.1f sec" % (len(dict), time.time() - startTime)

def writeSortedDict (fp, dict):
    """Write dictionary in frequency-sorted order"""
    startTime = time.time()
    for word in sorted(dict, key=(lambda w: dict[w]), reverse=True):
        print >>fp, "%s\t%d" % (word, dict[word])
    print "Wrote sorted list in %.1f sec" % (time.time() - startTime)

def main ():
    opts, args = getopt.getopt(sys.argv[1:], 'hw:d:u:o:', 
                               ['help', 'wiki=', 'dict=', 'dump=', 'out='])
    wikiFilename = _default_wiki_filename
    dictFilename = _default_dict_filename
    dumpFilename = _default_dump_filename
    outFilename = _default_out_filename
    for opt, arg in opts:
        if opt == '-h' or opt == '--help':
            printUsageAndExit()
        elif opt == '-w' or opt == '--wiki':
            wikiFilename = arg
        elif opt == '-d' or opt == '--dict':
            dictFilename = arg
        elif opt == '-u' or opt == '--dump':
            dumpFilename = arg
        elif opt == '-o' or opt == '--out':
            outFilename = arg
        else:
            print >>sys.stderr, 'Invalid option:', opt
            printUsageAndExit()
    if len(args) > 0:
        print >>sys.stderr, 'Extraneous parameters:', ' '.join(args)
        printUsageAndExit()

    startTime = time.time()

    # Load dictionary
    fp = codecs.open(dictFilename, 'r', 'utf8')
    dict = loadDict(fp)
    fp.close()

    # Parse Wikipedia article dump file to obtain word histogram
    fp = bz2.BZ2File(wikiFilename, 'r')
    hist = parseWikipediaDump(fp)
    fp.close()
    
    # Dump raw Wikipedia histogram (backup)
    fp = open(dumpFilename, 'w')
    hist.dump(fp)
    fp.close()
    
    # Augment proper dictionary with corresponding frequencies, if found
    # The Wikipedia corpus may contain several typos or improper words,
    # so we clean up by cross-referencing with a proper dictionary
    crossReference (dict, hist)
    
    # Write out final dictionary
    fp = open(outFilename, 'w')
    writeSortedDict(fp, dict)
    fp.close()
    
    print >>logfp, "Total processing time %.1f sec" % (time.time() - startTime)
        
if __name__ == '__main__':
    main()

