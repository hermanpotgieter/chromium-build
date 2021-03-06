// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.bytecode;

import static org.chromium.bytecode.TypeUtils.ASSERTION_ERROR;
import static org.chromium.bytecode.TypeUtils.BUILD_HOOKS;
import static org.chromium.bytecode.TypeUtils.VOID;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * An ClassVisitor for replacing Java ASSERT statements with a function by modifying Java bytecode.
 *
 * We do this in two steps, first step is to enable assert.
 * Following bytecode is generated for each class with ASSERT statements:
 * 0: ldc #8 // class CLASSNAME
 * 2: invokevirtual #9 // Method java/lang/Class.desiredAssertionStatus:()Z
 * 5: ifne 12
 * 8: iconst_1
 * 9: goto 13
 * 12: iconst_0
 * 13: putstatic #2 // Field $assertionsDisabled:Z
 * Replaces line #13 to the following:
 * 13: pop
 * Consequently, $assertionsDisabled is assigned the default value FALSE.
 * This is done in the first if statement in overridden visitFieldInsn. We do this per per-assert.
 *
 * Second step is to replace assert statement with a function:
 * The followed instructions are generated by a java assert statement:
 * getstatic     #3     // Field $assertionsDisabled:Z
 * ifne          118    // Jump to instruction as if assertion if not enabled
 * ...
 * ifne          19
 * new           #4     // class java/lang/AssertionError
 * dup
 * ldc           #5     // String (don't have this line if no assert message given)
 * invokespecial #6     // Method java/lang/AssertionError.
 * athrow
 * Replace athrow with:
 * invokestatic  #7     // Method org/chromium/base/JavaExceptionReporter.assertFailureHandler
 * goto          118
 * JavaExceptionReporter.assertFailureHandler is a function that handles the AssertionError,
 * 118 is the instruction to execute as if assertion if not enabled.
 */
class AssertionEnablerClassAdapter extends ClassVisitor {
    AssertionEnablerClassAdapter(ClassVisitor visitor) {
        super(Opcodes.ASM5, visitor);
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, String desc,
            String signature, String[] exceptions) {
        return new RewriteAssertMethodVisitorWriter(
                Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions));
    }

    static class RewriteAssertMethodVisitorWriter extends MethodVisitor {
        static final String ASSERTION_DISABLED_NAME = "$assertionsDisabled";
        static final String INSERT_INSTRUCTION_NAME = "assertFailureHandler";
        static final String INSERT_INSTRUCTION_DESC =
                TypeUtils.getMethodDescriptor(VOID, ASSERTION_ERROR);
        static final boolean INSERT_INSTRUCTION_ITF = false;

        boolean mStartLoadingAssert;
        Label mGotoLabel;

        public RewriteAssertMethodVisitorWriter(int api, MethodVisitor mv) {
            super(api, mv);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (opcode == Opcodes.PUTSTATIC && name.equals(ASSERTION_DISABLED_NAME)) {
                super.visitInsn(Opcodes.POP); // enable assert
            } else if (opcode == Opcodes.GETSTATIC && name.equals(ASSERTION_DISABLED_NAME)) {
                mStartLoadingAssert = true;
                super.visitFieldInsn(opcode, owner, name, desc);
            } else {
                super.visitFieldInsn(opcode, owner, name, desc);
            }
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (mStartLoadingAssert && opcode == Opcodes.IFNE && mGotoLabel == null) {
                mGotoLabel = label;
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitInsn(int opcode) {
            if (!mStartLoadingAssert || opcode != Opcodes.ATHROW) {
                super.visitInsn(opcode);
            } else {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BUILD_HOOKS, INSERT_INSTRUCTION_NAME,
                        INSERT_INSTRUCTION_DESC, INSERT_INSTRUCTION_ITF);
                super.visitJumpInsn(Opcodes.GOTO, mGotoLabel);
                mStartLoadingAssert = false;
                mGotoLabel = null;
            }
        }
    }
}