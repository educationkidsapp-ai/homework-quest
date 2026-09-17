package quest.server.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kotlinx.serialization.SerializersKt;
import kotlinx.serialization.descriptors.PolymorphicKind;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.descriptors.SerialKind;
import kotlinx.serialization.descriptors.StructureKind;
import org.springframework.stereotype.Component;

/**
 * springdoc builds a schema by reflecting over the Java bean, but the shared-api bodies are written by the kotlinx
 * codec (`SchemaValidator.json`, see {@link Json}) — and the two disagree in two ways:
 *
 * <ul>
 *   <li>an enum is written as its `@SerialName` (`"published"`), not its JVM constant (`PUBLISHED`);</li>
 *   <li>a computed Kotlin property is a bean getter but not a serialized element, so reflection invents fields that
 *       never reach the wire (`Course.key`, `Stop.category`).</li>
 * </ul>
 *
 * This converter keeps springdoc's structure — including the `oneOf` it derives from a sealed hierarchy — and only
 * corrects it against the `SerialDescriptor`, which is exactly what the codec writes. Anything it cannot read falls
 * through untouched, and a class without `@Serializable` is never looked at (the dashboard's Java DTOs are Jackson's).
 */
@Component
public class KotlinxSchemaConverter implements ModelConverter {
    /** `SchemaValidator.json` sets `classDiscriminator = "type"`: on the wire, but not an element of the descriptor. */
    private static final String DISCRIMINATOR = "type";

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (resolved == null) return null;
        Class<?> raw = rawClass(type);
        if (raw == null || raw.getAnnotation(kotlinx.serialization.Serializable.class) == null) return resolved;
        SerialDescriptor descriptor;
        try { descriptor = SerializersKt.serializer(raw).getDescriptor(); } catch (RuntimeException | LinkageError e) { return resolved; }

        Schema model = model(resolved, context);
        if (model == null) return resolved;
        if (descriptor.getKind() instanceof SerialKind.ENUM) { model.setEnum(elementNames(descriptor)); return resolved; }
        var kind = descriptor.getKind();
        if (!(kind instanceof StructureKind.CLASS || kind instanceof StructureKind.OBJECT || kind instanceof PolymorphicKind)) return resolved;   // a value class, a primitive wrapper (`LocalDate`), a list: nothing to prune
        Set<String> allowed = allowed(descriptor);
        if (allowed.isEmpty()) return resolved;
        prune(model, allowed);
        if (model.getAllOf() != null) for (Object part : List.copyOf(model.getAllOf())) prune((Schema) part, allowed);
        return resolved;
    }

    /** A `$ref` points at the model the context already holds; that is the one the document publishes. */
    private Schema model(Schema resolved, ModelConverterContext context) {
        if (resolved.get$ref() == null) return resolved;
        String name = resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1);
        return context.getDefinedModels().get(name);
    }

    /** The names the codec writes: a class's own elements, or — for a sealed hierarchy — what every subtype shares. */
    private Set<String> allowed(SerialDescriptor descriptor) {
        Set<String> names = new LinkedHashSet<>();
        if (descriptor.getKind() instanceof PolymorphicKind) {
            SerialDescriptor subtypes = descriptor.getElementsCount() > 1 ? descriptor.getElementDescriptor(1) : null;
            if (subtypes == null || subtypes.getElementsCount() == 0) return Set.of();
            for (int i = 0; i < subtypes.getElementsCount(); i++) {
                var shared = new LinkedHashSet<>(elementNames(subtypes.getElementDescriptor(i)));
                if (i == 0) names.addAll(shared); else names.retainAll(shared);
            }
        } else {
            names.addAll(elementNames(descriptor));
        }
        names.add(DISCRIMINATOR);   // written for a sealed subtype, and harmless on a plain class that has no such field
        return names;
    }

    private List<String> elementNames(SerialDescriptor descriptor) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < descriptor.getElementsCount(); i++) out.add(descriptor.getElementName(i));
        return out;
    }

    private void prune(Schema schema, Set<String> allowed) {
        if (schema.getProperties() != null) schema.getProperties().keySet().removeIf(name -> !allowed.contains(name));
        if (schema.getRequired() != null) schema.getRequired().removeIf(name -> !allowed.contains(name));
    }

    private Class<?> rawClass(AnnotatedType type) {
        if (type.getType() == null) return null;
        try { return io.swagger.v3.core.util.Json.mapper().constructType(type.getType()).getRawClass(); } catch (RuntimeException e) { return null; }
    }
}
