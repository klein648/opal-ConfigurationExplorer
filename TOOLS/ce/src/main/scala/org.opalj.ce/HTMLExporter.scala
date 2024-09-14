package org.opalj
package ce

import java.io.File
import java.io.PrintWriter
import java.nio.file.Path
import scala.collection.mutable.ListBuffer
import scala.io.Source

/**
 * Exports the Config structure into an HTML file
 * @param ConfigList Accepts a List of parsed Configuration Files
 * @param templatePath Accepts a Path to the HTML Template that should be used
 */
class HTMLExporter(ConfigList: ListBuffer[ConfigNode], templatePath: Path) {
    /**
     * Exports the ConfigList into an HTML file
     * The following parameters are all read from the Configuration Explorer config, however, the CE config was not handed over due to namespace conflicts with the internally used ConfigNode
     * @param exportFile Accepts a Path to the file that the Config shall be written to
     * @param HTMLHeadline Accepts the Headline HTML structure that gets passed on to the ConfigNodes
     * @param HTMLContent Accepts the Content HTML structure that gets passed on to the ConfigNodes
     */
    def exportHTML(exportFile : File, HTMLHeadline : String, HTMLContent : String): Unit = {

        // Generate HTML
        var fileContent = ""
        val template = Source.fromFile(templatePath.toString).getLines().mkString("\n")
        var body = ""
        for(config <- ConfigList)
        {
            if(config.isEmpty() == false) {
                body += config.toHTML("", HTMLHeadline, HTMLContent)
                body += "<hr>"
            }
        }
        fileContent = template.replace("$body",body)

        // Write to file
        val printWriter = new PrintWriter(exportFile)
        printWriter.write(fileContent)
        printWriter.close
    }

}
