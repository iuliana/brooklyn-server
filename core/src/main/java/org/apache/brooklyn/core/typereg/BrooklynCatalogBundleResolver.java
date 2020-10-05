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
package org.apache.brooklyn.core.typereg;

import com.google.common.annotations.Beta;
import java.io.File;
import java.util.List;
import java.util.ServiceLoader;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry;
import org.apache.brooklyn.api.typereg.BrooklynTypeRegistry.RegisteredTypeKind;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.api.typereg.RegisteredTypeLoadingContext;
import org.apache.brooklyn.core.mgmt.ManagementContextInjectable;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult;

/**
 * Interface for use by schemes which provide the capability to add types to the type registry.
 * Typically this is by installing an OSGi bundle with metadata, or sometimes with a YAML file.
 * <p>
 * To add a new resolver for a bundle of types, simply create an implementation and declare it
 * as a java service (cf {@link ServiceLoader}).
 * <p>
 * Implementations may wish to extend {@link AbstractCatalogBundleResolver} which simplifies the process.
 * <p>
 * See also {@link BrooklynTypePlanTransformer} for resolving individual types.
 */
public interface BrooklynCatalogBundleResolver extends ManagementContextInjectable {

    /** @return An identifier for the resolver.
     * This may be used when installing a bundle to target a specific resolver. */
    String getFormatCode();
    /** @return A display name for this resolver.
     * This may be used to prompt a user what type of bundle they are supplying. */
    String getFormatName();
    /** @return A description for this resolver */
    String getFormatDescription();

    /** 
     * Determines how appropriate is this transformer for the artifact.
     * The framework guarantees that the {@link File} exists.
     *  
     * @return A co-ordinated score / confidence value in the range 0 to 1. 
     * 0 means not compatible, 
     * 1 means this is clearly the intended transformer and no others need be tried 
     * (for instance because the format is explicitly specified),
     * and values between 0 and 1 indicate how likely a transformer believes it should be used.
     * <p>
     * Values greater than 0.5 are generally reserved for the presence of marker tags or files
     * which strongly indicate that the format is compatible.
     * Such a value should be returned even if the plan is not actually parseable, but if it looks like a user error
     * which prevents parsing (eg mal-formed YAML) and the transformer could likely be the intended target.
     * <p>
     * */
    double scoreForBundle(String format, @Nonnull File f);

    /** Installs the given bundle to the type {@link BrooklynTypeRegistry}.
     * <p>
     * The framework guarantees this will only be invoked when {@link #scoreForBundle(File)}
     * has returned a positive value.
     * <p>
     * Implementations should either return null or throw {@link UnsupportedCatalogBundleException}
     * if upon closer inspection following a non-null score, they do not actually support the given {@link File}.
     * If they should support the artifact but it contains an error, they should throw the relevant error for feedback to the user. */
    @Nullable
    @Beta  // return type is too detailed, but the detail is useful
    OsgiBundleInstallationResult create(@Nonnull File f);

}
