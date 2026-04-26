package com.agent.mapper;
import com.agent.entity.Article;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotesMapper extends BaseMapper<Article> {
}
