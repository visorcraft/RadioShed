package com.visorcraft.radioshed.source

/**
 * Android scanner file walk — delegates to the shared [JvmFileEnumerator].
 * SAF fallback when MEMS is denied remains future work.
 */
class AndroidFileEnumerator : FileEnumerator by JvmFileEnumerator()
