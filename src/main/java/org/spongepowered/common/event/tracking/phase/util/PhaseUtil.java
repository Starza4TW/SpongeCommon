/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.event.tracking.phase.util;

import org.apache.logging.log4j.Level;
import org.spongepowered.asm.util.PrettyPrinter;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.event.tracking.CauseTracker;
import org.spongepowered.common.event.tracking.PhaseContext;

import java.util.function.Supplier;

public final class PhaseUtil {

    public static Supplier<IllegalStateException> throwWithContext(String s, PhaseContext phaseContext) {
        return () -> {
            final PrettyPrinter printer = new PrettyPrinter(60);
            printer.add("Exception trying to process over a phase!").centre().hr();
            printer.addWrapped(40, "%s :", "PhaseContext");
            CauseTracker.CONTEXT_PRINTER.accept(printer, phaseContext);
            printer.add("Stacktrace:");
            final IllegalStateException exception = new IllegalStateException(s + " Please analyze the current phase context. ");
            printer.add(exception);
            printer.trace(System.err, SpongeImpl.getLogger(), Level.TRACE);
            return exception;
        };
    }

    private PhaseUtil() {
    }
}
