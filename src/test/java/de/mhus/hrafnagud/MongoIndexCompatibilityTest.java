package de.mhus.hrafnagud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;

/**
 * The two rules about indexes that a unit test can check and a deployment
 * cannot survive breaking.
 *
 * <p>Both were learned the same afternoon, both as a pod that would not start.
 * Spring's {@code auto-index-creation} builds every declared index when the
 * repository bean is created, so an index the <em>server</em> refuses is not a
 * slow query later — it is a boot failure, and only on a database that already
 * exists, which means never on a developer's laptop.
 *
 * <h2>Why a static check and not a Testcontainer</h2>
 * Booting against a real MongoDB 4.4 would catch more, and this project's own
 * doctrine keeps Testcontainers opt-in. The cheaper guard is enough for exactly
 * these two rules: both are decidable from the declaration, and both are the
 * ones that actually broke.
 *
 * <h2>The target server is MongoDB 4.4</h2>
 * Not by preference: MongoDB 5.0 and later require AVX, which the machine this
 * runs on does not have. So 4.4 is not a version to migrate off — it is the
 * floor and the ceiling. The Java driver still supports it (its minimum is
 * being raised <em>to</em> 4.4), so this is the bottom of the supported range
 * rather than below it.
 */
class MongoIndexCompatibilityTest {

    /**
     * What a {@code partialFilterExpression} may contain on 4.4: equality,
     * {@code $exists: true}, the range operators, {@code $type} and
     * {@code $and}. Notably absent — and the one that broke a deployment —
     * is {@code $in}, together with {@code $or} and the negations. They arrive
     * in MongoDB 6.0, and the server refuses the index rather than the query.
     */
    private static final Set<String> ALLOWED_IN_PARTIAL_FILTER = Set.of(
            "$exists", "$gt", "$gte", "$lt", "$lte", "$type", "$and", "$eq");

    /** One declared index, as the annotation says it. */
    private record DeclaredIndex(String collection, String name, Document keys,
            String partialFilter) {
    }

    @Test
    void no_partial_filter_uses_an_operator_mongodb_44_rejects() {
        List<String> violations = new ArrayList<>();

        for (DeclaredIndex index : declaredIndexes()) {
            if (index.partialFilter().isEmpty()) {
                continue;
            }
            for (String operator : operatorsIn(Document.parse(index.partialFilter()))) {
                if (!ALLOWED_IN_PARTIAL_FILTER.contains(operator)) {
                    violations.add("%s.%s uses %s in its partial filter: %s"
                            .formatted(index.collection(), index.name(), operator,
                                    index.partialFilter()));
                }
            }
        }

        assertThat(violations)
                .as("""
                        A partial index on MongoDB 4.4 accepts equality, \
                        $exists: true, the range operators, $type and $and — \
                        nothing else. $in and $or arrive in 6.0, and the \
                        server refuses the INDEX, so this is a boot failure on \
                        every existing database. Either narrow the filter or \
                        drop it: a collection small enough not to need one is \
                        the usual answer (see CategoryMappingDocument).""")
                .isEmpty();
    }

    /**
     * Two indexes on the same keys are rejected with error 85
     * ({@code IndexOptionsConflict}) whatever their options differ by — a
     * different name does not help, and neither does a different partial
     * filter. Newer servers are more permissive about the partial-filter case,
     * which is precisely why this needs a test rather than a local run.
     */
    @Test
    void no_two_indexes_of_one_collection_share_a_key_pattern() {
        Map<String, Map<Document, String>> perCollection = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();

        for (DeclaredIndex index : declaredIndexes()) {
            Map<Document, String> seen =
                    perCollection.computeIfAbsent(index.collection(), c -> new LinkedHashMap<>());
            String clash = seen.putIfAbsent(index.keys(), index.name());
            if (clash != null) {
                violations.add("%s: %s and %s both index %s"
                        .formatted(index.collection(), clash, index.name(), index.keys().toJson()));
            }
        }

        assertThat(violations)
                .as("""
                        MongoDB refuses a second index on the same keys, \
                        whatever the options say (error 85). Give the narrower \
                        one a key of its own — leading with the field its \
                        partial filter pins is both unique and the right shape \
                        for equality-then-sort (see ArticleDocument's \
                        translation_lifo_idx).""")
                .isEmpty();
    }

    /** Every index declared by an {@code @Document} class in this project. */
    private static List<DeclaredIndex> declaredIndexes() {
        List<DeclaredIndex> indexes = new ArrayList<>();
        for (Class<?> type : documentClasses()) {
            String collection = collectionOf(type);

            CompoundIndexes group = type.getAnnotation(CompoundIndexes.class);
            if (group != null) {
                for (CompoundIndex index : group.value()) {
                    indexes.add(new DeclaredIndex(collection, index.name(),
                            Document.parse(index.def()), index.partialFilter()));
                }
            }
            CompoundIndex single = type.getAnnotation(CompoundIndex.class);
            if (single != null) {
                indexes.add(new DeclaredIndex(collection, single.name(),
                        Document.parse(single.def()), single.partialFilter()));
            }
            // Field-level @Indexed produces { field: 1 }, which can collide
            // with a compound declaration just as well.
            for (var field : type.getDeclaredFields()) {
                Indexed indexed = field.getAnnotation(Indexed.class);
                if (indexed != null) {
                    indexes.add(new DeclaredIndex(collection, field.getName() + " (@Indexed)",
                            new Document(field.getName(), 1), ""));
                }
            }
        }
        assertThat(indexes).as("index declarations found by scanning").isNotEmpty();
        return indexes;
    }

    private static List<Class<?>> documentClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(
                org.springframework.data.mongodb.core.mapping.Document.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("de.mhus.hrafnagud")) {
            try {
                found.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new AssertionError("scanned class is not loadable: " + candidate, e);
            }
        }
        return found;
    }

    private static String collectionOf(Class<?> type) {
        var annotation =
                type.getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class);
        String named = annotation.collection().isEmpty() ? annotation.value() : annotation.collection();
        return named.isEmpty() ? type.getSimpleName() : named;
    }

    /** Every {@code $operator} anywhere in a filter, however deeply nested. */
    private static Set<String> operatorsIn(Object node) {
        Set<String> operators = new LinkedHashSet<>();
        if (node instanceof Document document) {
            document.forEach((key, value) -> {
                if (key.startsWith("$")) {
                    operators.add(key);
                }
                operators.addAll(operatorsIn(value));
            });
        } else if (node instanceof List<?> list) {
            list.forEach(element -> operators.addAll(operatorsIn(element)));
        }
        return operators;
    }
}
