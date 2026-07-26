package com.corp.mcp.admin.domain.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 通用分页结果。
 */
@Data
public class PageResult<T> {

    private long total;
    private long page;
    private long size;
    private List<T> list;

    public static <E> PageResult<E> of(IPage<E> page) {
        PageResult<E> r = new PageResult<>();
        r.setTotal(page.getTotal());
        r.setPage(page.getCurrent());
        r.setSize(page.getSize());
        r.setList(page.getRecords());
        return r;
    }

    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> mapper) {
        PageResult<V> r = new PageResult<>();
        r.setTotal(page.getTotal());
        r.setPage(page.getCurrent());
        r.setSize(page.getSize());
        r.setList(page.getRecords().stream().map(mapper).toList());
        return r;
    }
}
