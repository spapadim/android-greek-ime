#! /usr/bin/env python

import sys

_UNICODE_REPLACEMENT_CHAR = 0xfffd

encoding = 'iso8859-7'
if len(sys.argv) > 1:
    encoding = sys.argv[1]

isoToUnicode = \
    [ ord(unicode(chr(i), encoding, 'replace')) \
        for i in range(256)]

# Turn replacement char to zero
isoToUnicode = \
    [ i if i != _UNICODE_REPLACEMENT_CHAR else 0 \
        for i in isoToUnicode ]

unicodeMax = max(isoToUnicode)
unicodeToIso = \
    [ isoToUnicode.index(i) if i in isoToUnicode else 0 \
        for i in range(unicodeMax+1) ]

def printHexArray (list, fieldWidth, lineSize):
    formatStr = '0x%%0%dx,' % fieldWidth
    for i in range(len(list)):
        if i % lineSize == 0:
            print '\n   ',
        print formatStr % list[i],


print 'static unsigned short ISO_TO_UNICODE[] = {',
printHexArray(isoToUnicode, 4, 8)
print '};'
print
print 'static unsigned char UNICODE_TO_ISO[] = {',
printHexArray(unicodeToIso, 2, 12)
print '};'

