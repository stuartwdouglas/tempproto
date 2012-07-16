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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.classfilewriter.AccessFlag;
import org.jboss.classfilewriter.ClassFile;
import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.code.BranchEnd;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.classfilewriter.code.CodeLocation;
import org.jboss.classfilewriter.code.LookupSwitchBuilder;

/**
 * @author Stuart Douglas
 */
public class ParserGenerator {

    public static final String[] VALUES = {"GET", "PUT", "POST", "HEAD"};

    public static ValueParser createParser() {
        final ClassFile file = new ClassFile("org.jboss.SomeClass", "java.lang.Object", ValueParser.class.getName());

        final ClassMethod method = file.addMethod(Modifier.PUBLIC, "tokens", "Ljava/util/List;", "[B");

        final ClassMethod ctor = file.addMethod(AccessFlag.PUBLIC, "<init>", "V");
        ctor.getCodeAttribute().aload(0);
        ctor.getCodeAttribute().invokespecial(Object.class.getName(), "<init>", "()V");
        ctor.getCodeAttribute().returnInstruction();

        //list of all states except the initial
        final List<State> allStates = new ArrayList<State>();
        final State initial = new State((byte) 0, "");

        for (String value : VALUES) {
            addStates(initial, value, allStates);
        }

        if (0 == 0) {

            return new ValueParser() {
                @Override
                public List<String> tokens(final byte[] raw) {
                    State current = initial;
                    final List<String> ret = new ArrayList<String>();
                    StringBuilder b = null;
                    for (int i = 0; i < raw.length; ++i) {
                        byte value = raw[i];
                        if (current == null) {
                            if (value == ' ') {
                                ret.add(b.toString());
                            } else {
                                b.append((char)value);
                            }
                        } else {
                            if (value == ' ') {
                                if (current != initial) {
                                    ret.add(current.soFar);
                                    current = initial;
                                }
                            } else {
                                State next = current.next.get(value);
                                if (next != null) {
                                    current = next;
                                } else {
                                    b = new StringBuilder(current.soFar);
                                    b.append((char) value);
                                    current = null;
                                }
                            }
                        }
                    }
                    return ret;
                }
            };

        }


        createMethodBody(method.getCodeAttribute(), initial, allStates);

        final Class<ValueParser> cls = (Class<ValueParser>) file.define(ParserGenerator.class.getClassLoader());
        try {
            return cls.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static void createMethodBody(final CodeAttribute c, final State initialState, final List<State> allStates) {

        //create the return list and store it in local var 2
        c.newInstruction(ArrayList.class);
        c.dup();
        c.invokespecial(ArrayList.class.getName(), "<init>", "()V");
        c.astore(2);

        //our current index
        c.iconst(-1);
        c.istore(3);

        final Set<BranchEnd> defaultState = new HashSet<BranchEnd>();


        initialState.location = c.mark();
        //now out initial state
        writeOutState(c, initialState, initialState.location, defaultState);
        for (State state : allStates) {
            writeOutState(c, state, initialState.location, defaultState);
        }

        for (BranchEnd end : defaultState) {
            c.branchEnd(end);
        }
        final CodeLocation realDefaultStateStart = c.mark();
        c.aload(1);
        c.iinc(3, 1);
        c.iload(3);
        c.swap();
        c.arraylength();
        BranchEnd retEnd = c.ifIcmpne();
        //we are at the end of the array
        c.aload(2);
        c.returnInstruction();
        c.branchEnd(retEnd);
        c.aload(4);
        c.aload(1);
        c.iload(3);
        c.baload();
        c.dup();
        c.iconst(' ');
        final BranchEnd defaultStateEnd = c.ifIcmpeq();
        c.invokevirtual(StringBuilder.class.getName(), "append", "(C)Ljava/lang/StringBuilder;");
        c.pop();
        c.gotoInstruction(realDefaultStateStart);

        c.branchEnd(defaultStateEnd);
        c.pop2();
        c.aload(2);
        c.aload(4);
        c.invokevirtual(Object.class.getName(), "toString", "()Ljava/lang/String;");
        c.invokeinterface(List.class.getName(), "add", "(Ljava/lang/Object;)Z");
        c.pop();
        c.gotoInstruction(initialState.location);
    }

    private static void writeOutState(final CodeAttribute c, final State currentState, final CodeLocation initialState, final Set<BranchEnd> defaultState) {
        c.setupFrame("org.jboss.SomeClass", "[B", "Ljava/util/List;", "I");
        currentState.location = c.mark();
        for (AtomicReference<BranchEnd> fr : currentState.forwardReferences) {
            c.branchEnd(fr.get());
        }
        c.aload(1);
        c.iinc(3, 1);
        c.iload(3);
        c.swap();
        c.arraylength();
        BranchEnd retEnd = c.ifIcmpne();
        //we are at the end of the array
        c.aload(2);
        c.returnInstruction();
        c.branchEnd(retEnd);
        c.aload(1);
        c.iload(3);
        c.baload();

        final LookupSwitchBuilder s = new LookupSwitchBuilder();
        final AtomicReference<BranchEnd> tokenEnd = s.add((byte) ' ');
        for (final State state : currentState.next.values()) {
            if (state.location != null) {
                s.add(state.value, state.location);
            } else {
                state.forwardReferences.add(s.add(state.value));
            }
        }
        c.lookupswitch(s);

        //now we write out tokenEnd
        c.branchEnd(tokenEnd.get());
        if (!currentState.soFar.equals("")) {
            c.aload(2);
            c.ldc(currentState.soFar);
            c.invokeinterface(List.class.getName(), "add", "(Ljava/lang/Object;)Z");
            c.pop();
        }
        c.gotoInstruction(initialState);

        final BranchEnd defaultSetup = s.getDefaultBranchEnd().get();
        c.branchEnd(defaultSetup);
        c.newInstruction(StringBuilder.class);
        c.dup();
        c.ldc(currentState.soFar);
        c.invokespecial(StringBuilder.class.getName(), "<init>", "(Ljava/lang/String;)V");
        c.aload(1);
        c.iload(3);
        c.baload();
        c.invokevirtual(StringBuilder.class.getName(), "append", "(C)Ljava/lang/StringBuilder;");
        c.astore(4);
        defaultState.add(c.gotoInstruction());

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

    private static class State {

        final byte value;
        final String soFar;
        final Map<Byte, State> next = new HashMap<Byte, State>();
        final Set<AtomicReference<BranchEnd>> forwardReferences = new HashSet<AtomicReference<BranchEnd>>();
        CodeLocation location = null;

        private State(final byte value, final String soFar) {
            this.value = value;
            this.soFar = soFar;
        }
    }
}
