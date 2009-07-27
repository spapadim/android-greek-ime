OBTAINING DATA

The Wikipedia article dump can be downloaded from
    http://download.wikimedia.org/elwiki/
You want the *pages-articles.xml.bz2 file, which has just articles
(without talk pages or edit history).

The aspell dictionary data can be downloaded from
    ftp://ftp.gnu.org/gnu/aspell/dict/0index.html
You'll need to convert the main dictionary compressed word list (cwl)
file into plain UTF-8 encoded text:
    $ LANG=C word-list-compress d <el.cwl >el.txt
    $ iconv -f iso8859-7 -t utf8 <el.txt >el-utf8.txt


