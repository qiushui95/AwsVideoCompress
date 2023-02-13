package org.example

import java.util.regex.Pattern

fun main() {
    val input = "Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'C:\\Users\\97457\\Downloads\\d9d1440f-0459-4a65-9ae3-df93c8e2b84b.mp4':"

    val pattern = Pattern.compile("^\\s*Input #0, (\\w+).+\$\\s*")

    val matcher = pattern.matcher(input)

    if (matcher.matches()) {
        println(matcher.group(1))
    }
}