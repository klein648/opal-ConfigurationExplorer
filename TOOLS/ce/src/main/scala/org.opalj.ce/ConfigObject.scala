package org.opalj.ce

case class ConfigObject(entries: Map[String, ConfigNode], comment: Comment) extends ConfigNode {
    override def toHTML(label: String, HTMLHeadline : String, HTMLContent : String): String = {
        var HTMLString = ""
        var head = label
        if(this.comment.label.isEmpty == false) head = this.comment.label

        // Get HTML data for all child Nodes
        var content = "<p>" + comment.toHTML() + "</p>"
        for((key,node) <- entries){
            content += node.toHTML(key, HTMLHeadline, HTMLContent)
        }

        // Adds Header line with collapse + expand options
        HTMLString += HTMLHeadline.replace("$label",head).replace("$brief",this.comment.brief)

        // Add content below
        HTMLString += HTMLContent.replace("$content", content)

        HTMLString
    }

    override def isEmpty(): Boolean = {
        if(comment.isEmpty() == false) return false
        for((key,value) <- entries){
            if(value.isEmpty() == false) return false
        }
        true
    }
}