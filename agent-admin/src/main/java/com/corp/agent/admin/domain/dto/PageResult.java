package com.corp.agent.admin.domain.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

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
}
