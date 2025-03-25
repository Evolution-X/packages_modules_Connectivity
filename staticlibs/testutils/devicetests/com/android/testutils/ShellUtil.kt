/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("ShellUtil")

package com.android.testutils

import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream

/**
 * Run a command in a shell.
 *
 * Compared to [UiAutomation.executeShellCommand], this allows running commands with pipes and
 * redirections. [UiAutomation.executeShellCommand] splits the command on spaces regardless of
 * quotes, so it is not able to run commands like `sh -c "echo 123 > some_file"`.
 *
 * @param cmd Shell command to run.
 * @param shell Command used to run the shell.
 * @param outputProcessor Function taking stdout, stderr as argument. The streams will be closed
 *                        when this function returns.
 * @return Result of [outputProcessor].
 */
@RequiresApi(Build.VERSION_CODES.S) // executeShellCommandRw is 31+
fun <T> runCommandInShell(
    cmd: String,
    shell: String = "sh",
    outputProcessor: (InputStream) -> T,
): T {
    val (stdout, stdin) = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommandRw(shell)
    AutoCloseOutputStream(stdin).bufferedWriter().use { it.write(cmd) }
    AutoCloseInputStream(stdout).use { outStream ->
        return outputProcessor(outStream)
    }
}

/**
 * Run a command in a shell.
 *
 * Overload of [runCommandInShell] that reads and returns stdout as String.
 */
@RequiresApi(Build.VERSION_CODES.S)
fun runCommandInShell(
    cmd: String,
    shell: String = "sh",
) = runCommandInShell(cmd, shell) { stdout ->
    stdout.reader().use { it.readText() }
}

/**
 * Run a command in a root shell.
 *
 * This is generally only usable on devices on which [DeviceInfoUtils.isDebuggable] is true.
 * @see runCommandInShell
 */
@RequiresApi(Build.VERSION_CODES.S)
fun runCommandInRootShell(
    cmd: String
) = runCommandInShell(cmd, shell = "su root sh")
