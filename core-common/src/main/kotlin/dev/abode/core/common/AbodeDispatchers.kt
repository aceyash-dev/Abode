package dev.abode.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Single place to swap dispatchers in tests.
 *
 * Prediction runs on Default, never on Main: a dictionary lookup between the
 * touch event and the frame is the fastest way to make a keyboard feel slow.
 */
object AbodeDispatchers {
    val main: CoroutineDispatcher = Dispatchers.Main.immediate
    val prediction: CoroutineDispatcher = Dispatchers.Default
    val io: CoroutineDispatcher = Dispatchers.IO
}
