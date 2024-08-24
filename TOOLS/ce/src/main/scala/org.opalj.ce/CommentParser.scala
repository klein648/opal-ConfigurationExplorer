package org.opalj.ce

import java.nio.file.Path
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.control.Breaks.break

class CommentParser() {


    def parseComments(filePath: Path): ConfigNode = {
        val lines = Source.fromFile(filePath.toString()).getLines().toList
        val iterator = lines.iterator
        val (node,remains) = parseObject(iterator, "")
        node
    }

    def parseObject(iterator: Iterator[String], lastLine: String, currentComment : Comment) : (ConfigObject,String) = {
        val entries = mutable.Map[String, ConfigNode]()
        var line: String = lastLine

        while(iterator.hasNext){


        }

        currentComment.commitComments()
        (ConfigObject(entries.toMap, currentComment),"")
    }

    def parseEntry(iterator: Iterator[String], lastLine : String, currentComment : Comment) : (ConfigEntry,String) = {
        var line: String = lastLine
        var value = ""

        if (line.trim.startsWith("#") || line.trim.startsWith("//")) {
            currentComment.addComment(line.trim.stripPrefix("#").stripPrefix("//").trim)
            line = iterator.next()
        } else if (line.trim.startsWith("\"")) {
            val openedvalue = line.trim.stripPrefix("\"") // Located the opening Bracket of the value, but the closing bracket has not been found yet
            val (newline,newvalue) = this.extractValue(iterator,openedvalue,'\"')
            line = newline
            value = newvalue
            if(line.trim.startsWith("#") || line.trim.startsWith("//")){
                currentComment.addComment(line.trim.stripPrefix("#").stripPrefix("//").trim)
                line = ""
            }
        } else if (line.trim.startsWith("\'")) {
            val openedvalue = line.trim.substring(1) // Located the opening Bracket of the value, but the closing bracket has not been found yet
            val (newline,newvalue) = this.extractValue(iterator,openedvalue,'\'')
            line = newline
            value = newvalue
            if(line.trim.startsWith("#") || line.trim.startsWith("//")){
                currentComment.addComment(line.trim.stripPrefix("#").stripPrefix("//").trim)
                line = ""
            }
        } else {
            val openedvalue = line.trim
            val (newline,newvalue) = this.extractValue(iterator,openedvalue,' ')
            line = newline
            value = newvalue
            if(line.trim.startsWith("#") || line.trim.startsWith("//")){
                currentComment.addComment(line.trim.stripPrefix("#").stripPrefix("//").trim)
                line = ""
            }
        }

        currentComment.commitComments()
        (ConfigEntry(value,currentComment),line)
    }

    def parseList(iterator: Iterator[String], lastLine: String, currentComment : Comment) : (ConfigList,String) = {
        var line = lastLine
        val value = new ListBuffer[ConfigNode]

        // The comment for the next object may be parsed before the type of the next object is clear. Thus, the comment needs to be buffered in this method already
        var nextComment = new Comment()

        // If there is no further List that opens in the line, it can be assured that the next "]" is the closure of the list
        if(lastLine.contains("]") && !lastLine.contains("[")){

        }
        while(iterator.hasNext){
            if(line.trim.startsWith("{")){
                val (configobject,newline) = parseObject(iterator, line.trim.stripPrefix("{"), nextComment)
                value += configobject
                line = newline
            } else if (line.trim.startsWith("[")){
                val (configlist,newline) = parseList(iterator, line.trim.stripPrefix("["), nextComment)
                value += configlist
                line = newline
            } else if(line.trim.startsWith("//") || line.trim.startsWith("#")){
                nextComment.addComment(line.trim.stripPrefix("#").stripPrefix("//").trim)
                line = ""
            } else {
                val (configEntry,newline) = parseEntry(iterator, line.trim.stripPrefix(","), nextComment)
                value += configEntry
                line = newline
            }

            if(line.trim == ""){
                line = iterator.next()
            }
        }
        if(line.trim.startsWith("#") || line.trim.startsWith("//")){
            currentComment.addComment(line.trim.stripPrefix("#").stripPrefix("//").trim)
            line = ""
        }

        currentComment.commitComments()
        (ConfigList(value, currentComment),line)
    }

    private def extractValue(iterator: Iterator[String], line : String, terminatingSymbol : Char): (String,String) = {
        var value = ""
        var remainingLine = ""
        if(line.contains(terminatingSymbol)) {
            value = line.substring(0,line.indexOf(terminatingSymbol))
            remainingLine = line.substring(line.indexOf(terminatingSymbol))
        } else {
            value = line
            while(iterator.hasNext){
                remainingLine = iterator.next()
                if(remainingLine.contains(terminatingSymbol)){
                    value += remainingLine.trim.substring(0,remainingLine.trim.indexOf(terminatingSymbol))
                    remainingLine = remainingLine.trim.stripPrefix(remainingLine.trim.substring(0,remainingLine.trim.indexOf(terminatingSymbol)))
                    break
                } else {
                    value += remainingLine.trim()
                }
            }
        }
        (value,remainingLine)
    }
}