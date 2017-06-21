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
package org.apache.brooklyn.camp.brooklyn.spi.creation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.api.objs.SpecParameter;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampReservedKeys;
import org.apache.brooklyn.camp.brooklyn.spi.creation.service.CampServiceSpecResolver;
import org.apache.brooklyn.camp.spi.AbstractResource;
import org.apache.brooklyn.camp.spi.ApplicationComponentTemplate;
import org.apache.brooklyn.camp.spi.AssemblyTemplate;
import org.apache.brooklyn.camp.spi.PlatformComponentTemplate;
import org.apache.brooklyn.config.ConfigInheritance;
import org.apache.brooklyn.config.ConfigInheritances;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.config.ConfigValueAtContainer;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.config.BasicConfigInheritance;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.config.ConfigKeys.InheritanceContext;
import org.apache.brooklyn.core.config.internal.LazyContainerAndKeyValue;
import org.apache.brooklyn.core.mgmt.BrooklynTags;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.mgmt.EntityManagementUtils;
import org.apache.brooklyn.core.mgmt.ManagementContextInjectable;
import org.apache.brooklyn.core.mgmt.classloading.JavaBrooklynClassLoadingContext;
import org.apache.brooklyn.core.resolve.entity.EntitySpecResolver;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.flags.FlagUtils;
import org.apache.brooklyn.util.core.flags.FlagUtils.FlagConfigKeyAndValueRecord;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

/**
 * This generates instances of a template resolver that use a {@link ServiceTypeResolver}
 * to parse the {@code serviceType} line in the template.
 */
public class BrooklynComponentTemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(BrooklynComponentTemplateResolver.class);

    private final BrooklynClassLoadingContext loader;
    private final ManagementContext mgmt;
    private final ConfigBag attrs;
    private final Maybe<AbstractResource> template;
    private final BrooklynYamlTypeInstantiator.Factory yamlLoader;
    private final String type;
    private final AtomicBoolean alreadyBuilt = new AtomicBoolean(false);
    private final EntitySpecResolver serviceSpecResolver;

    private BrooklynComponentTemplateResolver(BrooklynClassLoadingContext loader, ConfigBag attrs, AbstractResource optionalTemplate, String type) {
        this.loader = loader;
        this.mgmt = loader.getManagementContext();
        this.attrs = ConfigBag.newInstanceCopying(attrs);
        this.template = Maybe.fromNullable(optionalTemplate);
        this.yamlLoader = new BrooklynYamlTypeInstantiator.Factory(loader, this);
        this.type = type;
        this.serviceSpecResolver = new CampServiceSpecResolver(mgmt, getServiceTypeResolverOverrides());
    }

    // Deprecated because want to keep as much of the state private as possible
    // Can't remove them because used by ServiceTypeResolver implementations
    /** @deprecated since 0.9.0 */
    @Deprecated public ManagementContext getManagementContext() { return mgmt; }
    @Deprecated public ConfigBag getAttrs() { return attrs; }
    @Deprecated public BrooklynYamlTypeInstantiator.Factory getYamlLoader() { return yamlLoader; }
    @Deprecated public String getDeclaredType() { return type; }

    public static class Factory {

        public static BrooklynComponentTemplateResolver newInstance(BrooklynClassLoadingContext context, Map<String, ?> childAttrs) {
            return newInstance(context, ConfigBag.newInstance(childAttrs), null);
        }

        public static BrooklynComponentTemplateResolver newInstance(BrooklynClassLoadingContext context, AbstractResource template) {
            return newInstance(context, ConfigBag.newInstance(template.getCustomAttributes()), template);
        }

        public static BrooklynComponentTemplateResolver newInstance(BrooklynClassLoadingContext context, String serviceType) {
            return newInstance(context, ConfigBag.newInstance().configureStringKey("serviceType", serviceType), null);
        }

        private static BrooklynComponentTemplateResolver newInstance(BrooklynClassLoadingContext context, ConfigBag attrs, AbstractResource optionalTemplate) {
            String type = getDeclaredType(null, optionalTemplate, attrs);
            return new BrooklynComponentTemplateResolver(context, attrs, optionalTemplate, type);
        }

        private static String getDeclaredType(String knownServiceType, AbstractResource optionalTemplate, @Nullable ConfigBag attrs) {
            String type = knownServiceType;
            if (type==null && optionalTemplate!=null) {
                type = optionalTemplate.getType();
                if (type.equals(AssemblyTemplate.CAMP_TYPE) || type.equals(PlatformComponentTemplate.CAMP_TYPE) || type.equals(ApplicationComponentTemplate.CAMP_TYPE))
                    // ignore these values for the type; only subclasses are interesting
                    type = null;
            }
            if (type==null) type = extractServiceTypeAttribute(attrs);
            return type;
        }

        private static String extractServiceTypeAttribute(@Nullable ConfigBag attrs) {
            return BrooklynYamlTypeInstantiator.InstantiatorFromKey.extractTypeName("service", attrs).orNull();
        }
    }

    public boolean canResolve() {
        return serviceSpecResolver.accepts(type, loader);
    }

    public <T extends Entity> EntitySpec<T> resolveSpec(Set<String> encounteredRegisteredTypeSymbolicNames) {
        if (alreadyBuilt.getAndSet(true))
            throw new IllegalStateException("Spec can only be used once: "+this);

        EntitySpec<?> spec = serviceSpecResolver.resolve(type, loader, encounteredRegisteredTypeSymbolicNames);

        if (spec == null) {
            // Try to provide some troubleshooting details
            final String msgDetails;
            RegisteredType item = mgmt.getTypeRegistry().get(Strings.removeFromStart(type, "catalog:"));
            String proto = Urls.getProtocol(type);
            if (item != null && encounteredRegisteredTypeSymbolicNames.contains(item.getSymbolicName())) {
                msgDetails = "Cycle between catalog items detected, starting from " + type +
                        ". Other catalog items being resolved up the stack are " + encounteredRegisteredTypeSymbolicNames +
                        ". Tried loading it as a Java class instead but failed.";
            } else if (proto != null) {
                if (BrooklynCampConstants.YAML_URL_PROTOCOL_WHITELIST.contains(proto)) {
                    // TODO propagate exception so we can provide better error messages
                    msgDetails = "The reference " + type + " looks like a URL (running the CAMP Brooklyn assembly-template instantiator) but couldn't load it (missing or invalid syntax?). " +
                            "It's also neither a catalog item nor a java type.";
                } else if ("brooklyn".equals(proto)){
                    msgDetails = "The reference " + type + " is not a registered catalog item nor a java type.";
                } else {
                    msgDetails = "The reference " + type + " looks like a URL (running the CAMP Brooklyn assembly-template instantiator) but the protocol " +
                            proto + " isn't white listed (" + BrooklynCampConstants.YAML_URL_PROTOCOL_WHITELIST + "). " +
                            "It's also neither a catalog item nor a java type.";
                }
            } else {
                msgDetails = "No resolver knew how to handle it. Using resolvers: " + serviceSpecResolver;
            }
            throw new IllegalStateException("Unable to create spec for type " + type + ". " + msgDetails);
        }
        spec = EntityManagementUtils.unwrapEntity(spec);

        populateSpec(spec, encounteredRegisteredTypeSymbolicNames);

        @SuppressWarnings("unchecked")
        EntitySpec<T> typedSpec = (EntitySpec<T>) spec;
        return typedSpec;
    }

    private List<EntitySpecResolver> getServiceTypeResolverOverrides() {
        List<EntitySpecResolver> overrides = new ArrayList<>();
        // none for now -- previously supported ServiceTypeResolver service
        return overrides;
    }

    @SuppressWarnings("unchecked")
    private <T extends Entity> void populateSpec(EntitySpec<T> spec, Set<String> encounteredRegisteredTypeIds) {
        String name, source=null, templateId=null, planId=null;
        if (template.isPresent()) {
            name = template.get().getName();
            templateId = template.get().getId();
            source = template.get().getSourceCode();
        } else {
            name = (String)attrs.getStringKey("name");
        }
        planId = (String)attrs.getStringKey("id");
        if (planId==null)
            planId = (String) attrs.getStringKey(BrooklynCampConstants.PLAN_ID_FLAG);

        Object childrenObj = attrs.getStringKey(BrooklynCampReservedKeys.BROOKLYN_CHILDREN);
        if (childrenObj != null) {
            Iterable<Map<String,?>> children = (Iterable<Map<String,?>>)childrenObj;
            for (Map<String,?> childAttrs : children) {
                BrooklynComponentTemplateResolver entityResolver = BrooklynComponentTemplateResolver.Factory.newInstance(loader, childAttrs);
                // encounteredRegisteredTypeIds must contain the items currently being loaded (the dependency chain),
                // but not parent items in this type already resolved (this is because an item's definition should
                // not include itself here, as a defined child, as that would create an endless loop; 
                // use in a member spec is fine)
                EntitySpec<? extends Entity> childSpec = entityResolver.resolveSpec(encounteredRegisteredTypeIds);
                spec.child(EntityManagementUtils.unwrapEntity(childSpec));
            }
        }

        if (source!=null) {
            spec.tag(BrooklynTags.newYamlSpecTag(source));
        }

        if (!Strings.isBlank(name))
            spec.displayName(name);
        if (templateId != null)
            spec.configure(BrooklynCampConstants.TEMPLATE_ID, templateId);
        if (planId != null)
            spec.configure(BrooklynCampConstants.PLAN_ID, planId);

        List<LocationSpec<?>> locations = new BrooklynYamlLocationResolver(mgmt).resolveLocations(attrs.getAllConfig(), true);
        if (locations != null) {
            // override locations defined in the type if locations are specified here
            // empty list can be used by caller to clear, so they are inherited
            spec.clearLocations();
            spec.locationSpecs(locations);
        }

        decorateSpec(spec, encounteredRegisteredTypeIds);
    }

    private <T extends Entity> void decorateSpec(EntitySpec<T> spec, Set<String> encounteredRegisteredTypeIds) {
        new BrooklynEntityDecorationResolver.PolicySpecResolver(yamlLoader).decorate(spec, attrs, encounteredRegisteredTypeIds);
        new BrooklynEntityDecorationResolver.EnricherSpecResolver(yamlLoader).decorate(spec, attrs, encounteredRegisteredTypeIds);
        new BrooklynEntityDecorationResolver.InitializerResolver(yamlLoader).decorate(spec, attrs, encounteredRegisteredTypeIds);
        new BrooklynEntityDecorationResolver.SpecParameterResolver(yamlLoader).decorate(spec, attrs, encounteredRegisteredTypeIds);
        new BrooklynEntityDecorationResolver.TagsResolver(yamlLoader).decorate(spec, attrs, encounteredRegisteredTypeIds);

        configureEntityConfig(spec, encounteredRegisteredTypeIds);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void configureEntityConfig(final EntitySpec<?> spec, Set<String> encounteredRegisteredTypeIds) {
        // first take *recognised* flags and config keys from the top-level, and put them in the bag (of brooklyn.config)
        // attrs will contain only brooklyn.xxx properties when coming from BrooklynEntityMatcher.
        // Any top-level flags will go into "brooklyn.flags". When resolving a spec from $brooklyn:entitySpec
        // top level flags remain in place. Have to support both cases.
        //
        // For config values inherited from the super-type (be that the Java type or another catalog item
        // being extended), we lookup the config key to find out if the values should be merged, overridden or 
        // cleared.

        ConfigBag bag = ConfigBag.newInstance((Map<Object, Object>) attrs.getStringKey(BrooklynCampReservedKeys.BROOKLYN_CONFIG));
        ConfigBag bagFlags = ConfigBag.newInstanceCopying(attrs);
        if (attrs.containsKey(BrooklynCampReservedKeys.BROOKLYN_FLAGS)) {
            bagFlags.putAll((Map<String, Object>) attrs.getStringKey(BrooklynCampReservedKeys.BROOKLYN_FLAGS));
        }

        Collection<FlagConfigKeyAndValueRecord> topLevelApparentConfig = findAllFlagsAndConfigKeyValues(spec, bagFlags);
        for (FlagConfigKeyAndValueRecord r: topLevelApparentConfig) {
            if (r.getConfigKeyMaybeValue().isPresent())
                bag.putIfAbsent((ConfigKey)r.getConfigKey(), r.getConfigKeyMaybeValue().get());
            if (r.getFlagMaybeValue().isPresent())
                bag.putAsStringKeyIfAbsent(r.getFlagName(), r.getFlagMaybeValue().get());
        }


        // now set configuration for all the items in the bag
        Map<String, ConfigKey<?>> entityConfigKeys = findAllConfigKeys(spec);

        Collection<FlagConfigKeyAndValueRecord> records = findAllFlagsAndConfigKeyValues(spec, bag);
        Set<String> keyNamesUsed = new LinkedHashSet<String>();
        for (FlagConfigKeyAndValueRecord r: records) {
            // flags and config keys tracked separately, look at each (may be overkill but it's what we've always done)

            Function<Maybe<Object>, Maybe<Object>> rawConvFn = Functions.identity();
            if (r.getFlagMaybeValue().isPresent()) {
                final String flag = r.getFlagName();
                final ConfigKey<Object> key = (ConfigKey<Object>) r.getConfigKey();
                if (key==null) ConfigKeys.newConfigKey(Object.class, flag);
                final Object ownValueF = new SpecialFlagsTransformer(loader, encounteredRegisteredTypeIds).apply(r.getFlagMaybeValue().get());

                Function<EntitySpec<?>, Maybe<Object>> rawEvalFn = new Function<EntitySpec<?>,Maybe<Object>>() {
                    @Override
                    public Maybe<Object> apply(EntitySpec<?> input) {
                        return spec.getFlags().containsKey(flag) ? Maybe.of((Object)spec.getFlags().get(flag)) : Maybe.absent();
                    }
                };
                Iterable<? extends ConfigValueAtContainer<EntitySpec<?>,Object>> ckvi = MutableList.of(
                        new LazyContainerAndKeyValue<EntitySpec<?>,Object>(key, null, rawEvalFn, rawConvFn));

                ConfigValueAtContainer<EntitySpec<?>,Object> combinedVal = ConfigInheritances.resolveInheriting(
                        null, key, Maybe.ofAllowingNull(ownValueF), Maybe.<Object>absent(),
                        ckvi.iterator(), InheritanceContext.TYPE_DEFINITION, getDefaultConfigInheritance()).getWithoutError();

                spec.configure(flag, combinedVal.get());
                keyNamesUsed.add(flag);
            }

            if (r.getConfigKeyMaybeValue().isPresent()) {
                final ConfigKey<Object> key = (ConfigKey<Object>) r.getConfigKey();
                final Object ownValueF = new SpecialFlagsTransformer(loader, encounteredRegisteredTypeIds).apply(r.getConfigKeyMaybeValue().get());

                Function<EntitySpec<?>, Maybe<Object>> rawEvalFn = new Function<EntitySpec<?>,Maybe<Object>>() {
                    @Override
                    public Maybe<Object> apply(EntitySpec<?> input) {
                        return spec.getConfig().containsKey(key) ? Maybe.of(spec.getConfig().get(key)) : Maybe.absent();
                    }
                };
                Iterable<? extends ConfigValueAtContainer<EntitySpec<?>,Object>> ckvi = MutableList.of(
                        new LazyContainerAndKeyValue<EntitySpec<?>,Object>(key, null, rawEvalFn, rawConvFn));

                ConfigValueAtContainer<EntitySpec<?>,Object> combinedVal = ConfigInheritances.resolveInheriting(
                        null, key, Maybe.ofAllowingNull(ownValueF), Maybe.<Object>absent(),
                        ckvi.iterator(), InheritanceContext.TYPE_DEFINITION, getDefaultConfigInheritance()).getWithoutError();

                spec.configure(key, combinedVal.get());
                keyNamesUsed.add(key.getName());
            }
        }

        // For anything that should not be inherited, clear it from the spec (if not set above)
        // (very few things follow this, esp not on the spec; things like camp.id do;
        // the meaning here is essentially that the given config cannot be stored in a parent spec)
        for (Map.Entry<String, ConfigKey<?>> entry : entityConfigKeys.entrySet()) {
            if (keyNamesUsed.contains(entry.getKey())) {
                continue;
            }
            ConfigKey<?> key = entry.getValue();
            if (!ConfigInheritances.isKeyReinheritable(key, InheritanceContext.TYPE_DEFINITION)) {
                spec.removeConfig(key);
                spec.removeFlag(key.getName());
            }
        }

        // set unused keys as anonymous config keys -
        // they aren't flags or known config keys, so must be passed as config keys in order for
        // EntitySpec to know what to do with them (as they are passed to the spec as flags)
        for (String key: MutableSet.copyOf(bag.getUnusedConfig().keySet())) {
            // we don't let a flag with the same name as a config key override the config key
            // (that's why we check whether it is used)
            if (!keyNamesUsed.contains(key)) {
                Object transformed = new SpecialFlagsTransformer(loader, encounteredRegisteredTypeIds).apply(bag.getStringKey(key));
                spec.configure(ConfigKeys.newConfigKey(Object.class, key.toString()), transformed);
            }
        }
    }

    protected ConfigInheritance getDefaultConfigInheritance() {
        return BasicConfigInheritance.OVERWRITE;
    }

    /**
     * Searches for config keys in the type, additional interfaces and the implementation (if specified)
     */
    private Collection<FlagConfigKeyAndValueRecord> findAllFlagsAndConfigKeyValues(EntitySpec<?> spec, ConfigBag bagFlags) {
        // Matches the bagFlags against the names used in brooklyn.parameters, entity configKeys  
        // and entity fields with `@SetFromFlag`.
        //
        // Returns all config keys / flags that match things in bagFlags, including duplicates.
        // For example, if a configKey in Java is re-declared in YAML `brooklyn.parameters`,
        // then we'll get two records.
        //
        // Make some effort to have these returned in the right order. That is very important
        // because they are added to the `EntitySpec.configure(key, val)`. If there is already
        // a key in `EntitySpec.config`, then the put will replace the value and leave the key
        // as-is (so the default-value and description of the key will remain as whatever the
        // first put said).

        // TODO We should remove duplicates, rather than just doing the `put` multiple times, 
        // relying on ordering. We should also respect the ordered returned by 
        // EntityDynamicType.getConfigKeys, which is much better (it respects `BasicConfigKeyOverwriting` 
        // etc).
        //  Or rather if the parameter fields are incomplete they might be merged with those defined 
        // on the type (eg description, default value) or ancestor, so that it isn't necessary for users
        // to re-declare those in a parameter definition, just anything they wish to overwrite.
        // 
        // However, that is hard/fiddly because of the way a config key can be referenced by
        // its real name or flag-name.
        // 
        // I wonder if this is fundamentally broken (and I really do dislike our informal use 
        // of aliases). Consider a configKey with name A and alias B. The bagFlags could have 
        // {A: val1, B: val2}. There is no formal definition of which takes precedence. We'll add 
        // both to the entity's configBag, without any warning - it's up to the `config().get()` 
        // method to then figure out what to do. It gets worse if there is also a ConfigKey with 
        // name "B" the "val2" then applies to both!
        //
        // I plan to propose a change on dev@brooklyn, to replace `@SetFromFlag`!

        // need to de-dupe? (can't use Set bc FCKAVR doesn't impl equals/hashcode)
        // TODO merge *bagFlags* with existing spec params, merge yaml w yaml parent params elsewhere
        List<FlagConfigKeyAndValueRecord> allKeys = MutableList.of();
        allKeys.addAll(FlagUtils.findAllParameterConfigKeys(spec.getParameters(), bagFlags));
        if (spec.getImplementation() != null) {
            allKeys.addAll(FlagUtils.findAllFlagsAndConfigKeys(null, spec.getImplementation(), bagFlags));
        }
        allKeys.addAll(FlagUtils.findAllFlagsAndConfigKeys(null, spec.getType(), bagFlags));
        for (Class<?> iface : spec.getAdditionalInterfaces()) {
            allKeys.addAll(FlagUtils.findAllFlagsAndConfigKeys(null, iface, bagFlags));
        }
        return allKeys;
    }

    private Map<String, ConfigKey<?>> findAllConfigKeys(EntitySpec<?> spec) {
        // TODO use in BasicSpecParameter to resolve ancestor config keys ?
        Set<Class<?>> types = MutableSet.<Class<?>>builder()
                .add(spec.getImplementation())
                .add(spec.getType())
                .addAll(spec.getAdditionalInterfaces())
                .remove(null)
                .build();
        // order above is important, respected below to take the first one defined 
        MutableMap<String, ConfigKey<?>> result = MutableMap.copyOf(FlagUtils.findAllConfigKeys(null, types));

        // put parameters atop config keys
        // TODO currently at this point parameters have been merged with ancestor spec parameters,
        // but *not* with config keys defined on the java type
        // see comments in BasicSpecParameter;
        // one way to fix would be to record in BasicSpecParameter which type fields are explicitly set
        // and to do a further merge here with  result.remove(param.getConfigKey().getName());
        // another way, probably simpler, would be to do the above result computation in BasicSpecParameter
        for (SpecParameter<?> param : spec.getParameters()) {
            result.put(param.getConfigKey().getName(), param.getConfigKey());
        }
        return result;
    }

    public static class SpecialFlagsTransformer implements Function<Object, Object> {
        protected final ManagementContext mgmt;
        /* TODO find a way to make do without loader here?
         * it is not very nice having to serialize it; but serialization of BLCL is now relatively clean.
         *
         * it is only used to instantiate classes, and now most types should be registered;
         * the notable exception is when one entity in a bundle is creating another in the same bundle,
         * it wants to use his bundle CLC to do that.  but we can set up some unique reference to the entity
         * which can be used to find it from mgmt, rather than pass the loader.
         */
        private BrooklynClassLoadingContext loader = null;
        private Set<String> encounteredRegisteredTypeIds;

        public SpecialFlagsTransformer(BrooklynClassLoadingContext loader, Set<String> encounteredRegisteredTypeIds) {
            this.loader = loader;
            mgmt = loader.getManagementContext();
            this.encounteredRegisteredTypeIds = encounteredRegisteredTypeIds;
        }
        @Override
        public Object apply(Object input) {
            if (input instanceof Map)
                return transformSpecialFlags((Map<?, ?>)input);
            else if (input instanceof Set<?>)
                return MutableSet.copyOf(transformSpecialFlags((Iterable<?>)input));
            else if (input instanceof List<?>)
                return MutableList.copyOf(transformSpecialFlags((Iterable<?>)input));
            else if (input instanceof Iterable<?>)
                return transformSpecialFlags((Iterable<?>)input);
            else
                return transformSpecialFlags(input);
        }

        protected Map<?, ?> transformSpecialFlags(Map<?, ?> flag) {
            return Maps.transformValues(flag, this);
        }

        protected Iterable<?> transformSpecialFlags(Iterable<?> flag) {
            return Iterables.transform(flag, this);
        }

        protected BrooklynClassLoadingContext getLoader() {
            if (loader!=null) return loader;
            // TODO currently loader will non-null unless someone has messed with the rebind files,
            // but we'd like to get rid of it; ideally we'd have a reference to the entity.
            // for now, this is a slightly naff way to do it, if we have to set loader=null as a workaround
            Entity entity = BrooklynTaskTags.getTargetOrContextEntity(Tasks.current());
            if (entity!=null) return CatalogUtils.getClassLoadingContext(entity);
            return JavaBrooklynClassLoadingContext.create(mgmt);
        }

        /**
         * Makes additional transformations to the given flag with the extra knowledge of the flag's management context.
         * @return The modified flag, or the flag unchanged.
         */
        protected Object transformSpecialFlags(Object flag) {
            if (flag instanceof EntitySpecConfiguration) {
                EntitySpecConfiguration specConfig = (EntitySpecConfiguration) flag;
                // TODO: This should called from BrooklynAssemblyTemplateInstantiator.configureEntityConfig
                // And have transformSpecialFlags(Object flag, ManagementContext mgmt) drill into the Object flag if it's a map or iterable?
                @SuppressWarnings("unchecked")
                Map<String, Object> resolvedConfig = (Map<String, Object>)transformSpecialFlags(specConfig.getSpecConfiguration());
                specConfig.setSpecConfiguration(resolvedConfig);
                EntitySpec<?> entitySpec = Factory.newInstance(getLoader(), specConfig.getSpecConfiguration()).resolveSpec(encounteredRegisteredTypeIds);

                return EntityManagementUtils.unwrapEntity(entitySpec);
            }
            if (flag instanceof ManagementContextInjectable) {
                log.debug("Injecting Brooklyn management context info object: {}", flag);
                ((ManagementContextInjectable) flag).setManagementContext(loader.getManagementContext());
            }

            return flag;
        }
    }
}
