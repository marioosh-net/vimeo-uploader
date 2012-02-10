vimeo-uploader
==============

Console uploader in Java for [Vimeo](http://vimeo.com/)

Installation
------------
Build app with [Maven](http://maven.apache.org)

<pre><code>mvn assembly:assembly</code></pre>

After successful build .jar is in target directory

<pre><code>cd target
java -jar vimeo-uploader.jar</code></pre>

Configuration
-------------
You need in Your home directory .vimeo-uploader configuration file.<br/> 
It will be created automatically when you first launch the application.

<pre><code>#vimeo-uploader configuration file
apiKey=APIKEY...
secret=SECRET...</code></pre>

Access Token will be saved in .vimeo-token file in Your home directory, unless You<br/> 
select option "no save token" (-ns).

Usage
-----
<pre><code>usage: java -jar vimeo-uploader.jar [options] <video file>
 -d &lt;descr&gt;   video description
 -h           help
 -ns          no save token
 -s           check status only (don't upload)
 -t &lt;title&gt;   video title
 -v           be verbose
</code></pre>