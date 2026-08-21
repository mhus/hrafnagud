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
 * The three rules about indexes that a unit test can check and a deployment
 * cannot survive breaking.
 *
 * <p>All three were learned the same way, as a pod that would not start.
 * Spring's {@code auto-index-creation} builds every declared index when the
 * repository bean is created, so an index the <em>server</em> refuses is not a
 * slow query later — it is a boot failure, and only on a database that already
 * exists, which means never on a developer's laptop.
 *
 * <h2>Why a static check and not a Testcontainer</h2>
 * Booting against a real MongoDB 4.4 would catch more, and this project's own
 * doctrine keeps Testcontainers opt-in. The cheaper guard is enough for exactly
 * these rules: all three are decidable from the declaration, and all three are
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

    /**
     * Index names this project has already created somewhere, over a key
     * pattern it no longer declares. Re-using one of them is error 86
     * ({@code IndexKeySpecsConflict}) — refused at creation, on every database
     * that holds the old index and on none that does not.
     *
     * <p>This is the one rule that lives between the code and a database rather
     * than inside the code, so the list is the part of the database the test is
     * allowed to know. The retired index itself stays behind costing write rate
     * until somebody drops it by hand; {@code CLAUDE.md} has the diffing recipe.
     *
     * <p><b>A name is retired in the same change that alters its pattern, never
     * afterwards.</b> Once the new pattern is live under the old name — which,
     * for anything already deployed, it is — a rename is the mirror-image
     * failure: same keys, different name, error 85, on precisely the databases
     * the rename was supposed to spare. That was learned by doing it, and it is
     * the reason this list is shorter than the history of pattern changes: a
     * change that already shipped under its old name has to keep it.
     */
    private static final Map<String, String> RETIRED_NAMES = Map.of(
            // Went with the switch to LIFO ordering of the translation queue.
            "translation_queue_idx", "{ translationNextAttemptAt: 1 }",
            // Folded into category_queue_idx, which has status as its prefix
            // and serves the counts-by-status queries too.
            "category_status_idx", "{ status: 1 }");

    /** One declared index, as the annotation says it. */
    private record DeclaredIndex(String collection, String name, Document keys,
            String partialFilter) {

        /**
         * The key pattern as MongoDB compares it — <b>in order</b>.
         *
         * <p>{@code Document} is a {@code LinkedHashMap}, so its {@code equals}
         * is map equality and blind to order. To the server
         * {@code { a: 1, b: -1 }} and {@code { b: -1, a: 1 }} are two different
         * indexes, and conflating them would report a clash that is not one.
         */
        String keyPattern() {
            return keys.toJson();
        }
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
        Map<String, Map<String, String>> perCollection = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();

        for (DeclaredIndex index : declaredIndexes()) {
            Map<String, String> seen =
                    perCollection.computeIfAbsent(index.collection(), c -> new LinkedHashMap<>());
            String clash = seen.putIfAbsent(index.keyPattern(), index.name());
            if (clash != null) {
                violations.add("%s: %s and %s both index %s"
                        .formatted(index.collection(), clash, index.name(), index.keyPattern()));
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

    /**
     * The rule that used to be written down and trusted to memory: an index
     * whose key pattern changes needs a new <em>name</em>.
     *
     * <p>Same name plus a different pattern is error 86
     * ({@code IndexKeySpecsConflict}), and unlike error 85 it does not depend on
     * the server version — it fails on any database that already holds the old
     * index, while a fresh schema and both other tests here stay green. So the
     * only thing a static check can do is refuse to re-use a name that was
     * once deployed, which is what {@link #RETIRED_NAMES} is for.
     */
    @Test
    void no_declared_index_reuses_a_retired_name() {
        List<String> violations = new ArrayList<>();

        for (DeclaredIndex index : declaredIndexes()) {
            String retiredPattern = RETIRED_NAMES.get(index.name());
            if (retiredPattern != null) {
                violations.add("%s.%s was retired over %s and is declared again over %s"
                        .formatted(index.collection(), index.name(), retiredPattern,
                                index.keyPattern()));
            }
        }

        assertThat(violations)
                .as("""
                        This index name has already been created over a \
                        different key pattern, so re-declaring it is error 86 \
                        (IndexKeySpecsConflict) on every database that holds \
                        the old one — and green on a fresh schema, which is why \
                        a local run never sees it. Pick a name that says what \
                        the new pattern leads with, and add the old \
                        name/pattern pair to RETIRED_NAMES.""")
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
