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

/**
 * The current state of the tokenizer state machine. This class is mutable and not thread safe.
 * <p/>
 * As the machine changes state this class is updated rather than allocating a new one each time.
 *
 * fields are not private to allow for efficient putfield / getfield access
 *
 * @author Stuart Douglas
 */
public class TokenState {

    public static final int NO_STATE = -1;
    public static final int PREFIX_MATCH = -2;

    /**
     * The current state in the tokenizer state machine.
     */
    int state;

    /**
     * If this state is a prefix or terminal match state this is set to the string
     * that is a candiate to be matched
     */
    String current;

    /**
     * If this state is a prefix match state then this holds the current position in the string.
     */
    int pos;

    /**
     * If this is in {@link #NO_STATE} then this holds the current token that has been read so far.
     */
    StringBuilder stringBuilder;

    public TokenState() {
        this.state = 0;
        this.current = null;
        this.pos = 0;
    }
}
