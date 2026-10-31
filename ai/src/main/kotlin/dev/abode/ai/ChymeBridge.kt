package dev.abode.ai

/**
 * The keyboard's side of the Chyme window.
 *
 * The window is an activity, so it has no input connection and cannot put text
 * into the field itself. The keyboard registers here while it is alive and the
 * window hands its result back through it — which works because the IME and the
 * app share one process, the decision ADR-002 already made.
 *
 * A null callback means the keyboard is gone: the window copies to the clipboard
 * instead of pretending it inserted something.
 */
object ChymeBridge {
    var insert: ((String) -> Unit)? = null
}
