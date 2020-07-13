package com.example.dao.service;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.example.domain.EventMessage;
import com.example.domain.EventMessageDetail;

@Mapper
public interface EventMessageDetailDao {

	@Select("select * from eventMessageDetail")
	public List<EventMessageDetail> findAll();

	@Select("SELECT * FROM eventMessageDetail WHERE id = #{id}")
	public EventMessageDetail findById(int id);

	@Delete("DELETE FROM eventMessageDetail WHERE id = #{id}")
	public int deleteById(long id);

	@Insert("INSERT INTO eventMessageDetail(id, severity) "
			+ " VALUES (#{id}, #{severity})")
	public int insert(EventMessageDetail eventMessageDetail);

	@Update("Update eventMessageDetail set severity=#{severity} "
			+ " where id=#{id}")
	public int update(EventMessageDetail eventMessageDetail);
	
}
