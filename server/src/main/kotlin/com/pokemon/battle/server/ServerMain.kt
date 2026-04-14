package com.pokemon.battle.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

fun main() {
    val input = BufferedReader(InputStreamReader(System.`in`))
    val output = PrintWriter(System.out, true)
    ServerSession(input, output).run()
}
