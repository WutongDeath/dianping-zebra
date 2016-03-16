package com.dianping.zebra.dao.mapper;

import java.util.List;
import java.util.concurrent.Future;

import org.apache.ibatis.annotations.Param;

import com.dianping.zebra.dao.AsyncDaoCallback;
import com.dianping.zebra.dao.annotation.TargetMethod;
import com.dianping.zebra.dao.entity.UserEntity;

/**
 * test async
 * @author damonzhu
 *
 */
public interface UserMapper {

	/**
	 *
	 */
	public void findUserById(@Param("userId") int userId, AsyncDaoCallback<UserEntity> callback);
	
	public UserEntity findUserById(@Param("userId") int userId);
	
	@TargetMethod(name = "findUserById")
	public Future<UserEntity> findUserByIdByFuture(@Param("userId") int userId);

	public List<UserEntity> getAll();

	@TargetMethod(name = "getAll")
	public Future<List<UserEntity>> getAll1();
	
	public int insertUser(UserEntity user);
	
	@TargetMethod(name = "insertUser")
	public void insertUser1(UserEntity user, AsyncDaoCallback<Integer> callback);

	@TargetMethod(name = "insertUser")
	public Future<Integer> insertUser2(UserEntity user);

}
