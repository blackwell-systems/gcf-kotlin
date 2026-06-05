package com.blackwellsystems.gcf

/**
 * Maps full kind names to short GCF abbreviations.
 */
val kindAbbrev: Map<String, String> = mapOf(
    "function" to "fn",
    "type" to "type",
    "method" to "method",
    "interface" to "iface",
    "var" to "var",
    "const" to "const",
    "resource" to "resource",
    "table" to "table",
    "class" to "class",
    "selector" to "selector",
    "field" to "field",
    "route_handler" to "route",
    "external" to "ext",
    "file" to "file",
    "package" to "pkg",
    "service" to "svc"
)

/**
 * Maps short GCF abbreviations to full kind names.
 */
val kindExpand: Map<String, String> = mapOf(
    "fn" to "function",
    "type" to "type",
    "method" to "method",
    "iface" to "interface",
    "var" to "var",
    "const" to "const",
    "resource" to "resource",
    "table" to "table",
    "class" to "class",
    "selector" to "selector",
    "field" to "field",
    "route" to "route_handler",
    "ext" to "external",
    "file" to "file",
    "pkg" to "package",
    "svc" to "service"
)

/**
 * Abbreviate a kind string. Returns the input unchanged if no abbreviation exists.
 */
fun abbreviateKind(kind: String): String = kindAbbrev[kind] ?: kind

/**
 * Expand a kind abbreviation. Returns the input unchanged if no expansion exists.
 */
fun expandKind(kind: String): String = kindExpand[kind] ?: kind
