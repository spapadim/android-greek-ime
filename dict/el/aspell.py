import sys
import popen2

logfp = sys.stderr

class aspell:
    def __init__ (self, lang='el', encoding = 'iso-8859-7'):
        self.encoding = encoding
        self.lang = lang
        self.p = popen2.Popen3('aspell --encoding=%s --lang=%s pipe' % (encoding, lang))
        # Skip first line
        self.p.fromchild.readline()

    def check (self, word):
        try:
            recodedWord = word.encode(self.encoding) + '\n'
        except UnicodeEncodeError:
            return []  # XXX
        self.p.tochild.write(recodedWord)
        self.p.tochild.flush()
        l = unicode(self.p.fromchild.readline().strip(), self.encoding)
        if len(l) == 0:  # Non-word was passed (e.g. only punctuation chars), aspell ignores
            return None
        self.p.fromchild.readline()  # Skip empty line
        if l[0] == '&':  # Correction suggestions given
            return l[l.index(':')+2:].split(', ')
        elif l[0] == '#':  # No suggestions found in dictionary
            return []
        else:
            return None

    __call__ = check

    def isValid (self, word):
        return self.check(word) == None

    def close (self):
        self.p.tochild.close()  # Send EOF to terminate process

    __del__ = close
    
