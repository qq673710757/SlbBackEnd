package com.slb.mining_backend.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVo<T> {
    private Long total;
    private Integer page;
    private Integer size;
    private List<T> list;
}

