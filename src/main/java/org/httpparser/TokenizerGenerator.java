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
import java.nio.ByteBuffer;
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


    private static final int BYTE_BUFFER_VAR = 1;
    private static final int BYTES_REMAINING_VAR = 2;
    private static final int TOKEN_STATE_VAR = 3;
    private static final int TOKEN_HANDLER_VAR = 4;
    private static final int CURRENT_STATE_VAR = 5;
    private static final int STATE_POS_VAR = 6;
    private static final int STATE_CURRENT_VAR = 7;
    private static final int STATE_STRING_BUILDER_VAR = 8;

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

        final ClassMethod handle = file.addMethod(Modifier.PUBLIC, "handle", "I", DescriptorUtils.makeDescriptor(ByteBuffer.class), "I", DescriptorUtils.makeDescriptor(TokenState.class), DescriptorUtils.makeDescriptor(TokenHandler.class));
        writeHandle(handle.getCodeAttribute(), initial, allStates, noStates);

        final Class<Tokenizer> cls = (Class<Tokenizer>) file.define(TokenizerGenerator.class.getClassLoader());
        try {
            return cls.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
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

    private static void writeHandle(final CodeAttribute c, final State initial, final List<State> allStates, int noStates) {

        final List<State> states = new ArrayList<State>();
        states.add(initial);
        states.addAll(allStates);
        Collections.sort(states);

        //store the current state in a local variable
        c.aload(TOKEN_STATE_VAR);
        c.dup();
        c.dup();
        c.dup();
        c.getfield(TokenState.class.getName(), "state", "I");
        c.istore(CURRENT_STATE_VAR);
        c.getfield(TokenState.class.getName(), "pos", "I");
        c.istore(STATE_POS_VAR);
        c.getfield(TokenState.class.getName(), "current", "Ljava/lang/String;");
        c.astore(STATE_CURRENT_VAR);
        c.getfield(TokenState.class.getName(), "stringBuilder", DescriptorUtils.makeDescriptor(StringBuilder.class));
        c.astore(STATE_STRING_BUILDER_VAR);

        final CodeLocation initialState = c.mark();
        c.iload(BYTES_REMAINING_VAR);
        final BranchEnd nonZero = c.ifne();

        //before we return we need to update the state number
        c.aload(TOKEN_STATE_VAR);
        c.iload(CURRENT_STATE_VAR);
        c.putfield(TokenState.class.getName(), "state", "I");

        //we have run out of bytes, return 0
        c.iconst(0);
        c.returnInstruction();

        c.branchEnd(nonZero);

        //load the current state
        c.iload(CURRENT_STATE_VAR);
        //switch on the current state
        TableSwitchBuilder builder = new TableSwitchBuilder(-BYTES_REMAINING_VAR, noStates);
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

        //return code
        //code that synchronizes the state object and returns
        c.setupFrame(DescriptorUtils.makeDescriptor("fakeclass"),
                DescriptorUtils.makeDescriptor(ByteBuffer.class.getName()),
                "I",
                DescriptorUtils.makeDescriptor(TokenState.class),
                DescriptorUtils.makeDescriptor(TokenHandler.class),
                "I",
                "I",
                DescriptorUtils.makeDescriptor(String.class),
                DescriptorUtils.makeDescriptor(StringBuilder.class));
        CodeLocation returnCode = c.mark();
        c.aload(TOKEN_STATE_VAR);
        c.dup();
        c.dup();
        c.dup();
        c.dup();

        c.iload(STATE_POS_VAR);
        c.putfield(TokenState.class.getName(), "pos", "I");
        c.aload(STATE_CURRENT_VAR);
        c.putfield(TokenState.class.getName(), "current", DescriptorUtils.makeDescriptor(String.class));
        c.aload(STATE_STRING_BUILDER_VAR);
        c.putfield(TokenState.class.getName(), "stringBuilder", DescriptorUtils.makeDescriptor(StringBuilder.class));
        c.iload(CURRENT_STATE_VAR);
        c.putfield(TokenState.class.getName(), "state", "I");
        c.iconst(0);
        c.returnInstruction();

        //prefix
        c.branchEnd(prefixMatch.get());

        final CodeLocation prefixLoop = c.mark(); //loop for when we are prefix matching
        handleReturnIfNoMoreBytes(c, returnCode);
        //load 3 copies of the current byte into the stack
        c.aload(BYTE_BUFFER_VAR);
        c.invokevirtual(ByteBuffer.class.getName(), "get", "()B");
        c.dup();
        c.dup();

        c.iinc(BYTES_REMAINING_VAR, -1);

        c.iconst(' ');
        BranchEnd prefixHandleSpace = c.ifIcmpeq();
        //check if we have overrun
        c.aload(STATE_CURRENT_VAR);
        c.invokevirtual(String.class.getName(), "length", "()I");
        c.iload(STATE_POS_VAR);
        BranchEnd overrun = c.ifIcmpeq();
        //so we have not overrun
        //now check if the character matches
        c.aload(STATE_CURRENT_VAR);
        c.iload(STATE_POS_VAR);
        c.invokevirtual(String.class.getName(), "charAt", "(I)C");
        c.isub();
        c.iconst(0); //just to make the stacks match
        c.swap();
        BranchEnd noMatch = c.ifne();

        //so they match
        c.pop2(); //pop our extra bytes off the stack, we do not need it
        c.iinc(STATE_POS_VAR, 1);
        handleReturnIfNoMoreBytes(c, returnCode);
        c.gotoInstruction(prefixLoop);

        c.branchEnd(overrun); //overrun and not match use the same code path
        c.branchEnd(noMatch); //the current character did not match
        c.iconst(TokenState.NO_STATE);
        c.istore(CURRENT_STATE_VAR);

        //create the string builder
        c.newInstruction(StringBuilder.class);
        c.dup();
        c.aload(STATE_CURRENT_VAR);
        c.iconst(0);
        c.iload(STATE_POS_VAR);
        c.invokevirtual(String.class.getName(), "substring", "(II)Ljava/lang/String;");
        c.invokespecial(StringBuilder.class.getName(), "<init>", "(Ljava/lang/String;)V");
        c.swap();
        c.invokevirtual(StringBuilder.class.getName(), "append", "(C)Ljava/lang/StringBuilder;");
        c.astore(STATE_STRING_BUILDER_VAR);
        c.pop();
        BranchEnd prefixToNoState = c.gotoInstruction();

        //handle the space case
        c.branchEnd(prefixHandleSpace);

        //new state will be 0
        c.iconst(0);
        c.istore(CURRENT_STATE_VAR);

        c.aload(STATE_CURRENT_VAR);
        c.invokevirtual(String.class.getName(), "length", "()I");
        c.iload(STATE_POS_VAR);
        BranchEnd correctLength = c.ifIcmpeq();

        c.aload(TOKEN_HANDLER_VAR);
        c.aload(STATE_CURRENT_VAR);
        c.iconst(0);
        c.iload(STATE_POS_VAR);
        c.invokevirtual(String.class.getName(), "substring", "(II)Ljava/lang/String;");
        c.invokeinterface(TokenHandler.class.getName(), "handleToken", "(Ljava/lang/String;)Z");
        //TODO: exit if it returns null
        //decrease the available bytes
        c.pop2();
        c.pop();
        tokenDone(c, initialState);

        c.branchEnd(correctLength);

        c.aload(TOKEN_HANDLER_VAR);
        c.aload(STATE_CURRENT_VAR);
        c.invokeinterface(TokenHandler.class.getName(), "handleToken", "(Ljava/lang/String;)Z");
        //TODO: exit if it returns null
        c.pop2();
        c.pop();
        tokenDone(c, initialState);


        //nostate
        c.branchEnd(noState.get());
        c.aload(TOKEN_STATE_VAR);
        c.getfield(TokenState.class.getName(), "stringBuilder", DescriptorUtils.makeDescriptor(StringBuilder.class));
        c.astore(STATE_STRING_BUILDER_VAR);
        c.branchEnd(prefixToNoState);
        CodeLocation noStateLoop = c.mark();

        //load 2 copies of the current byte into the stack
        c.aload(BYTE_BUFFER_VAR);
        c.invokevirtual(ByteBuffer.class.getName(), "get", "()B");
        c.dup();
        c.iinc(BYTES_REMAINING_VAR, -1);

        c.iconst(' ');
        BranchEnd nostateHandleSpace = c.ifIcmpeq();
        c.aload(STATE_STRING_BUILDER_VAR);
        c.swap();
        c.invokevirtual(StringBuilder.class.getName(), "append", "(C)Ljava/lang/StringBuilder;");
        c.pop();
        c.iload(BYTES_REMAINING_VAR);
        c.ifne(noStateLoop); //go back to the start if we have not run out of bytes

        //we have run out of bytes, so we need to write back the current state
        c.aload(TOKEN_STATE_VAR);
        c.dup();
        c.aload(STATE_STRING_BUILDER_VAR);
        c.putfield(TokenState.class.getName(), "stringBuilder", DescriptorUtils.makeDescriptor(StringBuilder.class));
        c.iload(CURRENT_STATE_VAR);
        c.putfield(TokenState.class.getName(), "state", "I");
        c.iconst(0);
        c.returnInstruction();

        c.branchEnd(nostateHandleSpace);
        c.aload(TOKEN_HANDLER_VAR);
        c.aload(STATE_STRING_BUILDER_VAR);
        c.invokevirtual(StringBuilder.class.getName(), "toString", "()Ljava/lang/String;");
        c.invokeinterface(TokenHandler.class.getName(), "handleToken", "(Ljava/lang/String;)Z");
        //TODO: exit if it returns null
        c.pop2();
        tokenDone(c, initialState);


        invokeState(c, ends.get(initial).get(), initial, initialState, noStateLoop, prefixLoop);
        for (final State s : states) {
            if (s.stateno >= 0) {
                invokeState(c, ends.get(s).get(), s, initialState, noStateLoop, prefixLoop);
            }
        }
    }

    private static void handleReturnIfNoMoreBytes(final CodeAttribute c, final CodeLocation returnCode) {
        c.iload(BYTES_REMAINING_VAR);
        c.ifEq(returnCode); //go back to the start if we have not run out of bytes
    }

    private static void tokenDone(final CodeAttribute c, final CodeLocation prefixLoop) {
        c.iconst(0);
        c.istore(CURRENT_STATE_VAR);

        c.iload(BYTES_REMAINING_VAR);
        c.ifne(prefixLoop); //go back to the start if we have not run out of bytes
        c.aload(TOKEN_STATE_VAR);
        c.iconst(0);
        c.putfield(TokenState.class.getName(), "state", "I");
        c.iconst(0);
        c.returnInstruction();
    }

    private static void invokeState(final CodeAttribute c, BranchEnd methodState, final State currentState, final CodeLocation start, final CodeLocation noStateStart, final CodeLocation prefixStart) {
        c.branchEnd(methodState);

        //load 2 copies of the current byte into the stack
        c.aload(BYTE_BUFFER_VAR);
        c.invokevirtual(ByteBuffer.class.getName(), "get", "()B");
        c.dup();
        c.iinc(BYTES_REMAINING_VAR, -1);

        final LookupSwitchBuilder s = new LookupSwitchBuilder();
        final AtomicReference<BranchEnd> tokenEnd = s.add((byte) ' ');
        final Map<State, AtomicReference<BranchEnd>> ends = new IdentityHashMap<State, AtomicReference<BranchEnd>>();
        for (final State state : currentState.next.values()) {
            ends.put(state, s.add(state.value));
        }
        c.lookupswitch(s);

        //now we write out tokenEnd
        c.branchEnd(tokenEnd.get());
        c.pop(); //opo off our extra byte, we don't need it
        if (!currentState.soFar.equals("")) {
            c.aload(TOKEN_HANDLER_VAR);
            c.ldc(currentState.soFar);
            c.invokeinterface(TokenHandler.class.getName(), "handleToken", "(Ljava/lang/String;)Z");
            //TODO: exit if it returns null
            c.pop();
            tokenDone(c, start);
        }
        c.gotoInstruction(start);

        final BranchEnd defaultSetup = s.getDefaultBranchEnd().get();
        c.branchEnd(defaultSetup);

        c.iconst(TokenState.NO_STATE);
        c.istore(CURRENT_STATE_VAR);

        //create the string builder
        c.newInstruction(StringBuilder.class);
        c.dup();
        c.ldc(currentState.soFar);
        c.invokespecial(StringBuilder.class.getName(), "<init>", "(Ljava/lang/String;)V");
        c.swap();
        c.invokevirtual(StringBuilder.class.getName(), "append", "(C)Ljava/lang/StringBuilder;");
        c.astore(STATE_STRING_BUILDER_VAR);
        c.gotoInstruction(noStateStart);


        for (Map.Entry<State, AtomicReference<BranchEnd>> e : ends.entrySet()) {
            c.branchEnd(e.getValue().get());
            c.pop();
            final State state = e.getKey();
            if (state.stateno < 0) {
                //prefix match
                c.iconst(state.stateno);
                c.istore(CURRENT_STATE_VAR);
                c.ldc(state.terminalState);
                c.astore(STATE_CURRENT_VAR);
                c.iconst(state.soFar.length());
                c.istore(STATE_POS_VAR);
                c.gotoInstruction(prefixStart);
            } else {
                c.iconst(state.stateno);
                c.istore(CURRENT_STATE_VAR);
                c.gotoInstruction(start);
            }
        }
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
