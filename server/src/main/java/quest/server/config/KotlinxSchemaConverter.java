package quest.server.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kotlinx.serialization.SerializersKt;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.descriptors.SerialKind;
import kotlinx.serialization.descriptors.StructureKind;
import org.springframework.stereotype.Component;

/**
 * springdoc builds a schema by reflecting over the Java bean, but the shared-api bodies are written by the kotlinx
 * codec (`SchemaValidator.json`, see {@link Json}) — and the two disagree in three ways:
 *
 * <ul>
 *   <li>an enum is written as its `@SerialName` (`"published"`), not its JVM constant (`PUBLISHED`);</li>
 *   <li>a computed Kotlin property is a bean getter but not a serialized element, so reflection invents fields that
 *       never reach the wire (`Course.key`, `Stop.category`, `Stop.SingleAnswer.correctId`);</li>
 *   <li>an abstract rung of a sealed hierarchy (`Stop.SingleAnswer`) is a bean, so reflection offers it as a variant
 *       of the union even though nothing can ever be encoded as one.</li>
 * </ul>
 *
 * A sealed type carries no serializer of its own for the second point, so what it really publishes is what all of its
 * concrete subtypes agree on: the fields every one of their descriptors declares, plus the discriminator. Everything
 * else keeps springdoc's structure — including the `oneOf` it derives from the hierarchy — and a class that is neither
 * `@Serializable` nor sealed is never looked at, so the dashboard's Jackson-served DTOs are untouched.
 */
@Component
public class KotlinxSchemaConverter implements ModelConverter {
    /** `SchemaValidator.json` sets `classDiscriminator = "type"`: on the wire, but not an element of any descriptor. */
    private static final String DISCRIMINATOR = "type";
    /** Simple names of the abstract rungs seen so far (`Stop`, `Stop.SingleAnswer`); a union may name one before we do. */
    private static final Set<String> ABSTRACT_RUNGS = ConcurrentHashMap.newKeySet();

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (resolved == null) return null;
        Class<?> raw = rawClass(type);
        if (raw == null) return resolved;
        boolean sealed = raw.getPermittedSubclasses() != null && raw.getPermittedSubclasses().length > 0;
        if (sealed && !concrete(raw)) ABSTRACT_RUNGS.add(raw.getSimpleName());
        dropAbstractVariants(resolved, Collections.newSetFromMap(new IdentityHashMap<>()));   // the union is assembled around this type, not for it
        if (!sealed && raw.getAnnotation(kotlinx.serialization.Serializable.class) == null) return resolved;

        Schema model = model(resolved, context);
        if (model == null) return resolved;
        Set<String> allowed = sealed ? sharedElements(raw) : ownElements(raw, model);
        if (allowed.isEmpty()) return resolved;
        prune(model, allowed);
        if (model.getAllOf() != null) for (Object part : List.copyOf(model.getAllOf())) prune((Schema) part, allowed);
        return resolved;
    }

    /**
     * The union springdoc derives from a sealed hierarchy is assembled while the enclosing property is resolved, so it
     * arrives here inside a list or an object rather than as the schema of the sealed type itself. Every abstract rung
     * it offers is dropped: only a concrete subtype can ever be on the wire. An `allOf` keeps its rung — that is the
     * subtype saying which fields it inherits, not a variant a caller could receive.
     */
    @SuppressWarnings("rawtypes")
    private void dropAbstractVariants(Schema schema, Set<Schema> seen) {
        if (schema == null || !seen.add(schema)) return;
        if (schema.getOneOf() != null) schema.getOneOf().removeIf(variant -> ABSTRACT_RUNGS.contains(refName(((Schema) variant).get$ref())));
        dropAbstractVariants(schema.getItems(), seen);
        descend(schema.getOneOf(), seen); descend(schema.getAllOf(), seen); descend(schema.getAnyOf(), seen);
        if (schema.getProperties() != null) for (Object property : List.copyOf(schema.getProperties().values())) dropAbstractVariants((Schema) property, seen);
    }

    @SuppressWarnings("rawtypes")
    private void descend(List nested, Set<Schema> seen) { if (nested != null) for (Object member : List.copyOf(nested)) dropAbstractVariants((Schema) member, seen); }

    /** What the codec writes for a concrete `@Serializable` class; `null` for anything whose descriptor says otherwise. */
    @SuppressWarnings("rawtypes")
    private Set<String> ownElements(Class<?> raw, Schema model) {
        SerialDescriptor descriptor = descriptor(raw);
        if (descriptor == null) return Set.of();
        if (descriptor.getKind() instanceof SerialKind.ENUM) { model.setEnum(elementNames(descriptor)); return Set.of(); }
        var kind = descriptor.getKind();
        if (!(kind instanceof StructureKind.CLASS || kind instanceof StructureKind.OBJECT)) return Set.of();   // a value class, a primitive wrapper (`LocalDate`), a list: nothing to prune
        return withDiscriminator(elementNames(descriptor));
    }

    /** What every concrete subtype of a sealed type writes — the only fields a caller can rely on seeing. */
    private Set<String> sharedElements(Class<?> raw) {
        Set<String> shared = null;
        for (Class<?> subtype : descendants(raw)) {
            if (!concrete(subtype)) continue;
            SerialDescriptor descriptor = descriptor(subtype);
            if (descriptor == null) return Set.of();
            var names = new LinkedHashSet<>(elementNames(descriptor));
            if (shared == null) shared = names; else shared.retainAll(names);
        }
        return shared == null || shared.isEmpty() ? Set.of() : withDiscriminator(List.copyOf(shared));
    }

    /** Every class under a sealed root, the abstract rungs included. */
    private List<Class<?>> descendants(Class<?> root) {
        List<Class<?>> out = new ArrayList<>();
        var permitted = root.getPermittedSubclasses();
        if (permitted == null) return out;
        for (Class<?> c : permitted) { out.add(c); out.addAll(descendants(c)); }
        return out;
    }

    private boolean concrete(Class<?> c) { return !c.isInterface() && !Modifier.isAbstract(c.getModifiers()); }

    private SerialDescriptor descriptor(Class<?> raw) {
        try { return SerializersKt.serializer(raw).getDescriptor(); } catch (RuntimeException | LinkageError e) { return null; }
    }

    private Set<String> withDiscriminator(List<String> names) {
        Set<String> out = new LinkedHashSet<>(names); out.add(DISCRIMINATOR); return out;
    }

    /** A `$ref` points at the model the context already holds; that is the one the document publishes. */
    @SuppressWarnings("rawtypes")
    private Schema model(Schema resolved, ModelConverterContext context) {
        if (resolved.get$ref() == null) return resolved;
        return context.getDefinedModels().get(refName(resolved.get$ref()));
    }

    private String refName(String ref) { return ref == null ? "" : ref.substring(ref.lastIndexOf('/') + 1); }

    private List<String> elementNames(SerialDescriptor descriptor) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < descriptor.getElementsCount(); i++) out.add(descriptor.getElementName(i));
        return out;
    }

    @SuppressWarnings("rawtypes")
    private void prune(Schema schema, Set<String> allowed) {
        if (schema.getProperties() != null) schema.getProperties().keySet().removeIf(name -> !allowed.contains(name));
        if (schema.getRequired() != null) schema.getRequired().removeIf(name -> !allowed.contains(name));
    }

    private Class<?> rawClass(AnnotatedType type) {
        if (type.getType() == null) return null;
        try { return io.swagger.v3.core.util.Json.mapper().constructType(type.getType()).getRawClass(); } catch (RuntimeException e) { return null; }
    }
}
