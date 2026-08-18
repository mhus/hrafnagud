package de.mhus.hrafnagud.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Paging envelope. Our own rather than Spring's {@code Page}, whose JSON
 * shape is an implementation detail that Spring has changed between major
 * versions and that leaks {@code Pageable}/{@code Sort} internals into the
 * contract.
 *
 * @param <T> element type
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageDto<T> {

    private List<T> items = new ArrayList<>();

    /** Zero-based page index. */
    private int page;

    private int size;

    /**
     * Total matching elements. {@code -1} when the query deliberately
     * skipped counting — an unfiltered count over the article collection is
     * a full scan and is not worth paying for on every page turn.
     */
    private long total;

    public static <T> PageDto<T> of(List<T> items, int page, int size, long total) {
        return PageDto.<T>builder()
                .items(items)
                .page(page)
                .size(size)
                .total(total)
                .build();
    }

    /** Maps the elements, preserving the paging metadata. */
    public <R> PageDto<R> map(Function<T, R> mapper) {
        return PageDto.<R>builder()
                .items(items.stream().map(mapper).toList())
                .page(page)
                .size(size)
                .total(total)
                .build();
    }
}
