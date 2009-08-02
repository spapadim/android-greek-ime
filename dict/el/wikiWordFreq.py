#! /usr/bin/env python

import sys
import bz2
import xml.sax
import re
import getopt
import codecs
import time
import aspell

logfp = sys.stderr

################################################################
# Histogram word handlers
################################################################

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

class WordHandler:
    def onWord (self, word):
        raise RuntimeError("Unimplemented abstract method")

class HistogramWordHandler (WordHandler):
    def __init__ (self, dict):
        self.dict = dict
        self._makeNormal()
    
    def _makeNormal (self):
        startTime = time.time()
        self._normMap = {}
        for word in self.dict:
            normWord = greekNormalize(word)
            if normWord not in self._normMap:
                self._normMap[normWord] = set()
            self._normMap[normWord].add(word)
        print >>logfp, "Finished normalization in %.1f sec" % (time.time() - startTime)
    
    def onWord (self, word):
        normWord = greekNormalize(word)
        if normWord not in self._normMap:
            return  # Ignore
        for word in self._normMap[normWord]:
            self.dict[word] += 1

class AspellWordHandler (WordHandler):
    def __init__ (self, dict):
        self.dict = dict
        self.spell = aspell.aspell()
    
    def onWord (self, word):
        #print >>logfp, "onWord:", word
        suggest = self.spell.check(word)
        if suggest != None and len(suggest) > 0:
            word = suggest[0]
        if word in self.dict:
            self.dict[word] += 1
    
    def __del__ (self):
        del self.spell  # XXX is this necessary?

################################################################
# Wikipedia XML dump parsing
################################################################

_wikilink_re = re.compile(r'\[\[(:?(?:[^:|\]]+:)+)?([^|\]]+\|)?([^\]]*)\]\]') # FIXME multi-colon
_wikimacro_re = re.compile(r'{{[^}]+}}')
_html_re = re.compile(r'</?[^>]+>|&[^;]+;|<!--.+?-->')
_ignore_chars_re = re.compile(r'[a-zA-Z0-9.,;:_?!\'"(){}\[\]|/#&%|~+=$*@<>\-]')

_progress_interval = 10  # Update progress message every so many word

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

class WikiParseHandler (xml.sax.ContentHandler):
    def __init__ (self, wordHandler, limit = sys.maxint):
        self.wordHandler = wordHandler
        self.cdata = ''
        self.inTextElement = False
        self.count = 0
        self.limit = limit
        
    def startDocument (self):
        print >>logfp, "Parsing:         0 articles",
    
    def endDocument (self):
        print >>logfp, ''

    def startElement (self, name, attrs):
        if name != 'text':
            return
        self.inTextElement = True
    
    def endElement (self, name):
        if name != 'text':
            return
        self.inTextElement = False
        self.count += 1
        if self.count >= self.limit:
            print >>logfp, ''
            raise StopParsingException()
        if self.count % _progress_interval == 0:
            print >>logfp, "\b"*20,
            print >>logfp, "%9d articles" % self.count,
        for word in stripWikiMarkup(self.cdata).split():
            self.wordHandler.onWord(word)
        self.cdata = ''
    
    def characters (self, content):
        if self.inTextElement:
            self.cdata += content

def parseWikipediaDump (fp, wordHandler, limit = sys.maxint):
    startTime = time.time()
    parseHandler = WikiParseHandler(wordHandler, limit)
    try:
        xml.sax.parse(fp, parseHandler)
    except StopParsingException:
        pass
    print >>logfp, "Processed %d articles in %.1f sec" % (parseHandler.count, time.time() - startTime)

################################################################
# Dictionary I/O
################################################################

def loadDict (fp, default = 0):
    """Load a Unix-style dictionary (one word per line) into a dictionary.
    Words are dictionary keys. Values are all initialized to the default"""
    startTime = time.time()
    dict = {}
    for l in fp:
        dict[l.strip()] = default
    print >>logfp, "Loaded %d words in %.1f sec" % (len(dict), time.time() - startTime)
    return dict

def writeSortedDict (fp, dict, scaleTo = None):
    """Write dictionary in frequency-sorted order"""
    startTime = time.time()
    freqSortedWords = sorted(dict, key=(lambda w: dict[w]), reverse=True)
    maxFreq = dict[freqSortedWords[0]]
    if scaleTo is None:
        scaleTo = maxFreq
    scaleFactor = float(scaleTo)/float(maxFreq)
    for word in freqSortedWords:
        print >>fp, "%s\t%d\t%d" % (word, int(round(dict[word]*scaleFactor)), dict[word])
    print "Wrote sorted list in %.1f sec" % (time.time() - startTime)

################################################################
# Main
################################################################

_default_wiki_filename = 'elwiki-20090712-pages-articles.xml.bz2'
_default_dict_filename = 'el-utf8.txt'
_default_out_filename = 'words_hist.txt'
_default_scale = 255

def printUsageAndExit ():
    print >>sys.stderr, 'Usage: %s [-w|--wiki file] [-d|--dict file]' % sys.argv[0]
    print >>sys.stderr, '  -w|--wiki  Wikipedia article compressed dump'
    print >>sys.stderr, '               (default: %s)' % _default_wiki_filename
    print >>sys.stderr, '  -d|--dict  Unix dictionary list (UTF textfile)'
    print >>sys.stderr, '               (default: %s)' % _default_dict_filename
    print >>sys.stderr, '  -o|--out   Dictinary histogram output filename'
    print >>sys.stderr, '               (default: %s)' % _default_out_filename
    print >>sys.stderr, '  -x|--scale Scale to specified maximum count'
    print >>sys.stderr, '               (default: %d)' % _default_scale
    print >>sys.stderr, '  -s|--smart Use aspell rather than simple normalization'
    print >>sys.stderr, '  -l|--limit Stop after processing given number of articles'
    print >>sys.stderr, '  -h|--help  Print this usage information and exit'  
    sys.exit(0)

def main ():
    opts, args = getopt.getopt(sys.argv[1:], 'hsw:d:o:l:x:', 
                               ['help', 'smart', 'wiki=', 'dict=', 'out=', 'limit=', 'scale='])
    wikiFilename = _default_wiki_filename
    dictFilename = _default_dict_filename
    outFilename = _default_out_filename
    scaleTo = _default_scale
    aspellMode = False
    articleLimit = sys.maxint
    for opt, arg in opts:
        if opt == '-h' or opt == '--help':
            printUsageAndExit()
        elif opt == '-s' or opt == '--smart':
            aspellMode = True  # Use aspell, instead of normalized representation
        elif opt == '-l' or opt == '--limit':
            articleLimit = int(arg)
        elif opt == '-w' or opt == '--wiki':
            wikiFilename = arg
        elif opt == '-d' or opt == '--dict':
            dictFilename = arg
        elif opt == '-o' or opt == '--out':
            outFilename = arg
        elif opt == '-x' or opt == '--scale':
            scaleTo = int(arg)
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
    if aspellMode:
        parseWikipediaDump(fp, AspellWordHandler(dict), articleLimit)
    else:
        parseWikipediaDump(fp, HistogramWordHandler(dict), articleLimit)
    fp.close()
    
    # Write out final dictionary
    fp = codecs.open(outFilename, 'w', 'utf8')
    writeSortedDict(fp, dict, scaleTo)
    fp.close()
    
    print >>logfp, "Total processing time %.1f sec" % (time.time() - startTime)
        
if __name__ == '__main__':
    main()

