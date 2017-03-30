/*******************************************************************************
 * Copyright (c) 2009, 2017 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Evgeny Mandrikov - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.internal.analysis.filter;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Filters code that is generated for try-with-resources statement.
 */
public final class TryWithResourcesFilter implements IFilter {

	public void filter(final MethodNode methodNode,
			final IFilterOutput output) {
		if (methodNode.tryCatchBlocks.isEmpty()) {
			return;
		}
		final Matcher matcher = new Matcher(output);
		for (TryCatchBlockNode t : methodNode.tryCatchBlocks) {
			if (t.type == null) {
				matcher.start(t.handler);
				if (!matcher.matchEcj()) {
					matcher.start(t.handler);
					matcher.matchEcjNoFlowOut();
				}
			} else if ("java/lang/Throwable".equals(t.type)) {
				for (Matcher.JavacPattern p : Matcher.JavacPattern.values()) {
					matcher.start(t.handler);
					if (matcher.matchJavac(p)) {
						break;
					}
				}
			}
		}
	}

	static class Matcher {
		private final IFilterOutput output;

		private final Map<String, VarInsnNode> vars = new HashMap<String, VarInsnNode>();
		private final Map<String, String> owners = new HashMap<String, String>();
		private final Map<String, LabelNode> labels = new HashMap<String, LabelNode>();

		private AbstractInsnNode start;
		private AbstractInsnNode cursor;

		Matcher(final IFilterOutput output) {
			this.output = output;
		}

		private enum JavacPattern {
			OPTIMAL, FULL, OMITTED_NULL_CHECK, METHOD,
		}

		private void start(final AbstractInsnNode start) {
			this.start = start;
			cursor = start.getPrevious();
			vars.clear();
			labels.clear();
			owners.clear();
		}

		private boolean matchJavac(final JavacPattern p) {
			// "catch (Throwable t)"
			if (!nextIsVar(Opcodes.ASTORE, "t1")) {
				return false;
			}
			// "primaryExc = t"
			if (!nextIsVar(Opcodes.ALOAD, "t1")) {
				return false;
			}
			if (!nextIsVar(Opcodes.ASTORE, "primaryExc")) {
				return false;
			}
			// "throw t"
			if (!nextIsVar(Opcodes.ALOAD, "t1")) {
				return false;
			}
			if (!nextIs(Opcodes.ATHROW)) {
				return false;
			}

			// "catch (any t)"
			if (!nextIsVar(Opcodes.ASTORE, "t2")) {
				return false;
			}
			if (!nextIsJavacClose(p, "e")) {
				return false;
			}
			// "throw t"
			if (!nextIsVar(Opcodes.ALOAD, "t2")) {
				return false;
			}
			if (!nextIs(Opcodes.ATHROW)) {
				return false;
			}

			final AbstractInsnNode end = cursor;
			AbstractInsnNode c = start.getPrevious();
			cursor = c;
			while (!nextIsJavacClose(p, "n")) {
				c = c.getPrevious();
				cursor = c;
				if (cursor == null) {
					return false;
				}
			}

			AbstractInsnNode m = cursor;
			next();
			if (cursor.getOpcode() != Opcodes.GOTO) {
				cursor = m;
			}

			output.ignore(c.getNext(), cursor);
			output.ignore(start.getNext(), end);
			return true;
		}

		/**
		 * On a first invocation will associate variables with names "r" and
		 * "primaryExc", on subsequent invocations will use those associations
		 * for checks.
		 */
		private boolean nextIsJavacClose(final JavacPattern p,
				final String ctx) {
			switch (p) {
			case METHOD:
			case FULL:
				// "if (r != null)"
				if (!(nextIsVar(Opcodes.ALOAD, "r")
						&& nextIs(Opcodes.IFNULL))) {
					return false;
				}
			}
			switch (p) {
			case METHOD:
			case OPTIMAL:
				if (nextIsVar(Opcodes.ALOAD, "primaryExc")
						&& nextIsVar(Opcodes.ALOAD, "r")
						&& nextIs(Opcodes.INVOKESTATIC)) {
					final MethodInsnNode m = (MethodInsnNode) cursor;
					return "$closeResource".equals(m.name)
							&& "(Ljava/lang/Throwable;Ljava/lang/AutoCloseable;)V"
									.equals(m.desc);
				}
				return false;
			case FULL:
			case OMITTED_NULL_CHECK:
				return nextIsVar(Opcodes.ALOAD, "primaryExc")
						// "if (primaryExc != null)"
						&& nextIs(Opcodes.IFNULL)
						// "r.close()"
						&& nextIsClose("r") && nextIs(Opcodes.GOTO)
						// "catch (Throwable t)"
						&& nextIsVar(Opcodes.ASTORE, ctx + "t")
						// "primaryExc.addSuppressed(t)"
						&& nextIsVar(Opcodes.ALOAD, "primaryExc")
						&& nextIsVar(Opcodes.ALOAD, ctx + "t")
						&& nextIsAddSuppressed() && nextIs(Opcodes.GOTO)
						// "r.close()"
						&& nextIsClose("r");
			default:
				return false;
			}
		}

		private boolean matchEcj() {
			// "catch (any primaryExc)"
			if (!nextIsVar(Opcodes.ASTORE, "primaryExc")) {
				return false;
			}
			if (!nextIsEcjCloseAndThrow("r0")) {
				return false;
			}

			AbstractInsnNode c;
			int resources = 1;
			String r = "r" + resources;
			c = cursor;
			while (nextIsEcjClose(r)) {
				if (!nextIsJump(Opcodes.GOTO, r + ".end")) {
					return false;
				}
				if (!nextIsEcjSuppress(r)) {
					return false;
				}
				if (!nextIsEcjCloseAndThrow(r)) {
					return false;
				}
				resources++;
				r = "r" + resources;
				c = cursor;
			}
			cursor = c;
			if (!nextIsEcjSuppress("last")) {
				return false;
			}
			// "throw primaryExc"
			if (!nextIsVar(Opcodes.ALOAD, "primaryExc")) {
				return false;
			}
			if (!nextIs(Opcodes.ATHROW)) {
				return false;
			}

			final AbstractInsnNode end = cursor;
			c = start.getPrevious();
			cursor = c;
			while (!nextIsEcjClose("r0")) {
				c = c.getPrevious();
				cursor = c;
				if (cursor == null) {
					return false;
				}
			}
			next();
			if (cursor.getOpcode() != Opcodes.GOTO) {
				return false;
			}

			output.ignore(c.getNext(), cursor);
			output.ignore(start.getNext(), end);
			return true;
		}

		private boolean matchEcjNoFlowOut() {
			// "catch (any primaryExc)"
			if (!nextIsVar(Opcodes.ASTORE, "primaryExc")) {
				return false;
			}

			AbstractInsnNode c;
			int resources = 0;
			String r = "r" + resources;
			c = cursor;
			while (nextIsEcjCloseAndThrow(r) && nextIsEcjSuppress(r)) {
				resources++;
				r = "r" + resources;
				c = cursor;
			}
			cursor = c;
			// "throw primaryExc"
			if (!nextIsVar(Opcodes.ALOAD, "primaryExc")) {
				return false;
			}
			if (!nextIs(Opcodes.ATHROW)) {
				return false;
			}

			final AbstractInsnNode end = cursor;
			c = start.getPrevious();
			cursor = c;
			while (!nextIsEcjClose("r0")) {
				c = c.getPrevious();
				cursor = c;
				if (cursor == null) {
					return false;
				}
			}
			for (int i = 1; i < resources; i++) {
				if (!nextIsEcjClose("r" + i)) {
					return false;
				}
			}

			output.ignore(c.getNext(), cursor);
			output.ignore(start, end);
			return true;
		}

		private boolean nextIsEcjClose(final String name) {
			return nextIsVar(Opcodes.ALOAD, name)
					// "if (r != null)"
					&& nextIsJump(Opcodes.IFNULL, name + ".end")
					// "r.close()"
					&& nextIsClose(name);
		}

		private boolean nextIsEcjCloseAndThrow(final String name) {
			return nextIsVar(Opcodes.ALOAD, name)
					// "if (r != null)"
					&& nextIsJump(Opcodes.IFNULL, name)
					// "r.close()"
					&& nextIsClose(name) && nextIsLabel(name)
					&& nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIs(Opcodes.ATHROW);
		}

		private boolean nextIsEcjSuppress(final String name) {
			final String suppressedExc = name + ".t";
			final String startLabel = name + ".suppressStart";
			final String endLabel = name + ".suppressEnd";
			return nextIsVar(Opcodes.ASTORE, suppressedExc)
					// "suppressedExc = t"
					// "if (primaryExc != null)"
					&& nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIsJump(Opcodes.IFNONNULL, startLabel)
					// "primaryExc = suppressedExc"
					&& nextIsVar(Opcodes.ALOAD, suppressedExc)
					&& nextIsVar(Opcodes.ASTORE, "primaryExc")
					&& nextIsJump(Opcodes.GOTO, endLabel)
					// "if (primaryExc == suppressedExc)"
					&& nextIsLabel(startLabel)
					&& nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIsVar(Opcodes.ALOAD, suppressedExc)
					&& nextIsJump(Opcodes.IF_ACMPEQ, endLabel)
					// "primaryExc.addSuppressed(suppressedExc)"
					&& nextIsAddSuppressed(suppressedExc)
					&& nextIsLabel(endLabel);
		}

		private boolean nextIsClose(final String name) {
			if (!nextIsVar(Opcodes.ALOAD, name)) {
				return false;
			}
			next();
			if (cursor.getOpcode() != Opcodes.INVOKEINTERFACE
					&& cursor.getOpcode() != Opcodes.INVOKEVIRTUAL) {
				return false;
			}
			final MethodInsnNode m = (MethodInsnNode) cursor;
			if (!"close".equals(m.name) || !"()V".equals(m.desc)) {
				return false;
			}
			final String actual = m.owner;
			final String expected = owners.get(name);
			if (expected == null) {
				owners.put(name, actual);
				return true;
			} else {
				return expected.equals(actual);
			}
		}

		private boolean nextIsAddSuppressed() {
			if (!nextIs(Opcodes.INVOKEVIRTUAL)) {
				return false;
			}
			final MethodInsnNode m = (MethodInsnNode) cursor;
			return "java/lang/Throwable".equals(m.owner)
					&& "addSuppressed".equals(m.name);
		}

		private boolean nextIsAddSuppressed(final String name) {
			return nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIsVar(Opcodes.ALOAD, name) && nextIsAddSuppressed();
		}

		private boolean nextIsVar(final int opcode, final String name) {
			if (!nextIs(opcode)) {
				return false;
			}
			final VarInsnNode actual = (VarInsnNode) cursor;
			final VarInsnNode expected = vars.get(name);
			if (expected == null) {
				vars.put(name, actual);
				return true;
			} else {
				return expected.var == actual.var;
			}
		}

		private boolean nextIsJump(final int opcode, final String name) {
			if (!nextIs(opcode)) {
				return false;
			}
			final LabelNode actual = ((JumpInsnNode) cursor).label;
			final LabelNode expected = labels.get(name);
			if (expected == null) {
				labels.put(name, actual);
				return true;
			} else {
				return expected == actual;
			}
		}

		private boolean nextIsLabel(final String name) {
			cursor = cursor.getNext();
			if (cursor.getType() != AbstractInsnNode.LABEL) {
				return false;
			}
			final LabelNode actual = (LabelNode) cursor;
			final LabelNode expected = labels.get(name);
			return expected == actual;
		}

		/**
		 * Moves {@link #cursor} to next instruction and returns
		 * <code>true</code> if it has given opcode.
		 */
		private boolean nextIs(final int opcode) {
			next();
			return cursor != null && cursor.getOpcode() == opcode;
		}

		/**
		 * Moves {@link #cursor} to next instruction.
		 */
		private void next() {
			do {
				cursor = cursor.getNext();
			} while (cursor != null
					&& (cursor.getType() == AbstractInsnNode.FRAME
							|| cursor.getType() == AbstractInsnNode.LABEL
							|| cursor.getType() == AbstractInsnNode.LINE));
		}
	}

}
