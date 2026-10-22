package io.github.swiderekartmax.seconddisplay

/**
 * Senders that paired with the on-screen code, as stored in preferences: one
 * "publicKey<TAB>name" entry each (base64 ed25519 keys contain no tab).
 */
internal object PairedSenders {
    fun keys(entries: Set<String>): List<String> =
        entries.map { it.substringBefore('\t') }.filter { it.isNotEmpty() }

    /** Adds or renames a sender; a key appears at most once. */
    fun add(entries: Set<String>, publicKey: String, name: String): Set<String> =
        entries.filterNot { it.substringBefore('\t') == publicKey }.toSet() + "$publicKey\t${name.replace('\t', ' ')}"
}
