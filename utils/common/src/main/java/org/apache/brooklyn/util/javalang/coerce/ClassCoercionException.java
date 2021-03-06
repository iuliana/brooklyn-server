/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.util.javalang.coerce;

/**
 * Thrown to indicate that {@link TypeCoercions} could not cast an object from one
 * class to another.
 */
public class ClassCoercionException extends ClassCastException {
    private static final long serialVersionUID = -4616045237993172497L;

    private final Throwable cause;
    
    public ClassCoercionException() {
        super();
        cause = null;
    }
    public ClassCoercionException(String s) {
        super(s);
        cause = null;
    }
    public ClassCoercionException(String s, Throwable cause) {
        super(s);
        this.cause = cause;
    }

    @Override
    public Throwable getCause() {
        return cause;
    }
    
}
