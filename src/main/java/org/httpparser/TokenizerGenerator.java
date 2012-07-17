/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.httpparser;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.classfilewriter.AccessFlag;
import org.jboss.classfilewriter.ClassFile;
import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.code.BranchEnd;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.classfilewriter.code.CodeLocation;
import org.jboss.classfilewriter.code.LookupSwitchBuilder;
import org.jboss.classfilewriter.code.TableSwitchBuilder;
import org.jboss.classfilewriter.util.DescriptorUtils;

/**
 * @author Stuart Douglas
 */
public class TokenizerGenerator {

    private static final AtomicInteger nameCounter = new AtomicInteger();

    public static Tokenizer createTokenizer(final String[] values) {
        final String className = Tokenizer.class.getName() + "$$" + nameCounter.incrementAndGet();
        final ClassFile file = new ClassFile(className, "java.lang.Object", Tokenizer.class.getName());

        final ClassMethod ctor = file.addMethod(AccessFlag.PUBLIC, "<init>", "V");
        ctor.getCodeAttribute().aload(0);
        ctor.getCodeAttribute().invokespecial(Object.class.getName(), "<init>", "()V");
        ctor.getCodeAttribute().returnInstruction();

        //list of all states except the initial
        final List<State> allStates = new ArrayList<State>();
        final State initial = new State((byte) 0, "");
        for (String value : values) {
            addStates(initial, value, allStates);
        }
        //we want initial to be number 0
        final AtomicInteger stateCounter = new AtomicInteger(-1);
        setupStateNo(initial, stateCounter);
        for (State state : allStates) {
            setupStateNo(state, stateCounter);
        }
        final int noStates = stateCounter.get();

        final ClassMethod handle = file.addMethod(Modifier.PUBLIC, "handle", "V", "[B", "I", "I", DescriptorUtils.makeDescriptor(TokenContext.class));
        writeHandle(className, handle.getCodeAttribute(), initial, allStates, noStates);

        writeStateMethod(file, initial);
        for (State state : allStates) {
            if (state.stateno > 0) {
                writeStateMethod(file, state);
            }
        }

        final Class<Tokenizer> cls = (Class<Tokenizer>) file.define(TokenizerGenerator.class.getClassLoader());
        try {
            return cls.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeStateMethod(final ClassFile file, final State currentState) {
        final ClassMethod handle = file.addMethod(Modifier.STATIC, "state" + currentState.stateno, "I", "[B", "I", "I", DescriptorUtils.makeDescriptor(TokenContext.class));
        final CodeAttribute c = handle.getCodeAttribute();
        c.aload(0);
        c.iload(1);
        c.baload();
        final LookupSwitchBuilder s = new LookupSwitchBuilder();
        final AtomicReference<BranchEnd> tokenEnd = s.add((byte) ' ');
        final Map<State, AtomicReference<BranchEnd>> ends = new IdentityHashMap<State, AtomicReference<BranchEnd>>();
        for (final State state : currentState.next.values()) {
            ends.put(state, s.add(state.value));
        }
        c.lookupswitch(s);

        //now we write out tokenEnd
        c.branchEnd(tokenEnd.get());
        if (!currentState.soFar.equals("")) {
            c.aload(3);
            c.ldc(currentState.soFar);
            c.invokestatic(TokenizerGenerator.class.getName(), "tokenEnd", "(" + DescriptorUtils.makeDescriptor(TokenContext.class) + "Ljava/lang/String;)V");
        }
        c.iconst(1);
        c.returnInstruction();

        final BranchEnd defaultSetup = s.getDefaultBranchEnd().get();
        c.branchEnd(defaultSetup);
        c.setupFrame("B", DescriptorUtils.makeDescriptor(TokenContext.class));
        c.aload(3);
        c.getfield(TokenContext.class.getName(), "state", TokenState.class);
        c.dup();
        c.iconst(TokenState.NO_STATE);
        c.putfield(TokenState.class.getName(), "state", "I");
        c.newInstruction(StringBuilder.class);
        c.dup();
        c.ldc(currentState.soFar);
        c.invokespecial(StringBuilder.class.getName(), "<init>", "(Ljava/lang/String;)V");
        c.aload(0);
        c.iload(1);
        c.baload();
        c.invokevirtual(StringBuilder.class.getName(), "append", "(C)Ljava/lang/StringBuilder;");
        c.putfield(TokenState.class.getName(), "stringBuilder", StringBuilder.class);
        c.iconst(1);
        c.returnInstruction();

        for (Map.Entry<State, AtomicReference<BranchEnd>> e : ends.entrySet()) {
            c.branchEnd(e.getValue().get());
            final State state = e.getKey();
            if (state.stateno < 0) {
                //prefix match
                c.aload(3);
                c.getfield(TokenContext.class.getName(), "state", TokenState.class);
                c.dup();
                c.dup();
                c.iconst(state.stateno);
                c.putfield(TokenState.class.getName(), "state", "I");
                c.ldc(state.terminalState);
                c.putfield(TokenState.class.getName(), "current", "Ljava/lang/String;");
                c.iconst(state.soFar.length());
                c.putfield(TokenState.class.getName(), "pos", "I");
                c.iconst(1);
                c.returnInstruction();
            } else {
                c.aload(3);
                c.getfield(TokenContext.class.getName(), "state", TokenState.class);
                c.iconst(state.stateno);
                c.putfield(TokenState.class.getName(), "state", "I");
                c.iconst(1);
                c.returnInstruction();
            }
        }

    }

    private static void setupStateNo(final State state, final AtomicInteger stateCounter) {
        if (state.next.isEmpty()) {
            state.stateno = TokenState.PREFIX_MATCH;
            state.terminalState = state.soFar;
        } else if (state.next.size() == 1) {
            String terminal = null;
            State s = state.next.values().iterator().next();
            while (true) {
                if (s.next.size() > 1) {
                    break;
                } else if (s.next.isEmpty()) {
                    terminal = s.soFar;
                    break;
                }
                s = s.next.values().iterator().next();
            }
            if (terminal != null) {
                state.stateno = TokenState.PREFIX_MATCH;
                state.terminalState = terminal;
            } else {
                state.stateno = stateCounter.incrementAndGet();
            }
        } else {
            state.stateno = stateCounter.incrementAndGet();
        }
    }

    private static void writeHandle(final String className, final CodeAttribute c, final State initial, final List<State> allStates, int noStates) {
        final List<State> states = new ArrayList<State>();
        states.add(initial);
        states.addAll(allStates);
        Collections.sort(states);
        final CodeLocation start = c.mark();
        c.iload(2);
        c.iload(3);
        final BranchEnd end = c.ifIcmplt();
        c.returnInstruction();
        c.branchEnd(end);

        c.aload(1);
        c.iload(2);
        c.iload(3);
        c.aload(4);
        c.dup();
        c.getfield(TokenContext.class.getName(), "state", TokenState.class);
        c.getfield(TokenState.class.getName(), "state", "I");
        TableSwitchBuilder builder = new TableSwitchBuilder(-2, noStates);
        final IdentityHashMap<State, AtomicReference<BranchEnd>> ends = new IdentityHashMap<State, AtomicReference<BranchEnd>>();
        final AtomicReference<BranchEnd> prefixMatch = builder.add();
        final AtomicReference<BranchEnd> noState = builder.add();

        ends.put(initial, builder.add());
        for (final State s : states) {
            if (s.stateno > 0) {
                ends.put(s, builder.add());
            }
        }
        c.tableswitch(builder);
        c.branchEnd(builder.getDefaultBranchEnd().get());
        c.newInstruction(RuntimeException.class);
        c.dup();
        c.ldc("Could not find state");
        c.invokespecial(RuntimeException.class.getName(), "<init>", "(Ljava/lang/String;)V");
        c.athrow();

        //prefix
        c.branchEnd(prefixMatch.get());
        c.invokestatic(TokenizerGenerator.class.getName(), "prefix", "([BII" + DescriptorUtils.makeDescriptor(TokenContext.class) + ")I");
        c.iload(2);
        c.iadd();
        c.istore(2);
        c.gotoInstruction(start);

        //none
        c.branchEnd(noState.get());
        c.invokestatic(TokenizerGenerator.class.getName(), "nostate", "([BII" + DescriptorUtils.makeDescriptor(TokenContext.class) + ")I");
        c.iload(2);
        c.iadd();
        c.istore(2);
        c.gotoInstruction(start);

        invokeState(className, c, ends, initial, start);
        for (final State s : states) {
            if (s.stateno >= 0) {
                invokeState(className, c, ends, s, start);
            }
        }
    }

    //These methods are invoked by generated bytecode
    @SuppressWarnings("unused")
    static int prefix(byte[] data, int p, int length, TokenContext context) {
        int datapos = p;
        final TokenState state = context.state;
        final String current = state.current;
        int pos = state.pos;
        int used = 1;
        while (datapos < length) {
            byte b = data[datapos];
            if (b == ' ') {
                if (current.length() == pos) {
                    tokenEnd(context, current);
                } else {
                    tokenEnd(context, current.substring(0, pos));
                }
                return used;
            } else {
                if (pos != current.length() && b == current.charAt(pos)) {
                    ++pos;
                } else {
                    state.state = TokenState.NO_STATE;
                    final StringBuilder builder = new StringBuilder(current.substring(0, pos));
                    builder.append((char) b);
                    state.stringBuilder = builder;
                    return used;
                }
            }
            datapos++;
            used++;
        }
        context.state.setPos(pos);
        return used;
    }

    @SuppressWarnings("unused")
    static int nostate(byte[] data, int p, int length, TokenContext context) {
        int datapos = p;
        int used = 1;
        final StringBuilder stringBuilder = context.state.stringBuilder;
        while (datapos < length) {
            byte b = data[datapos];
            if (b == ' ') {
                tokenEnd(context, stringBuilder.toString());
                return used;
            } else {
                stringBuilder.append((char) b);
            }
            ++used;
            ++datapos;
        }
        return used;
    }

    @SuppressWarnings("unused")
    static void tokenEnd(TokenContext context, String token) {
        final TokenState state = context.state;
        context.tokenHandler.handleToken(token, context);
        state.state = 0;
        state.stringBuilder = null;
    }

    private static void invokeState(final String className, final CodeAttribute c, final IdentityHashMap<State, AtomicReference<BranchEnd>> ends, final State s, final CodeLocation start) {
        c.branchEnd(ends.get(s).get());
        c.invokestatic(className, "state" + s.stateno, "([BII" + DescriptorUtils.makeDescriptor(TokenContext.class) + ")I");
        c.iload(2);
        c.iadd();
        c.istore(2);
        c.gotoInstruction(start);
    }

    private static void addStates(final State initial, final String value, final List<State> allStates) {
        addStates(initial, value, 0, allStates);
    }

    private static void addStates(final State current, final String value, final int i, final List<State> allStates) {
        if (i == value.length()) {
            return;
        }
        byte[] bytes = value.getBytes();
        final byte currentByte = bytes[i];
        State newState = current.next.get(currentByte);
        if (newState == null) {
            current.next.put(currentByte, newState = new State(currentByte, value.substring(0, i + 1)));
            allStates.add(newState);
        }
        addStates(newState, value, i + 1, allStates);
    }

    private static class State implements Comparable<State> {

        Integer stateno;
        String terminalState;
        final byte value;
        final String soFar;
        final Map<Byte, State> next = new HashMap<Byte, State>();

        private State(final byte value, final String soFar) {
            this.value = value;
            this.soFar = soFar;
        }

        @Override
        public int compareTo(final State o) {
            return stateno.compareTo(o.stateno);
        }
    }
}
