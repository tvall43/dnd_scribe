package com.example.dndscribe.data.repository

import com.example.dndscribe.data.local.SessionDao
import com.example.dndscribe.data.local.SessionEntity
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val sessionDao: SessionDao) {
    val allSessions: Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    suspend fun getSessionById(id: Long): SessionEntity? = sessionDao.getSessionById(id)

    suspend fun insertSession(session: SessionEntity): Long = sessionDao.insertSession(session)

    suspend fun updateSession(session: SessionEntity) = sessionDao.updateSession(session)

    suspend fun deleteSession(session: SessionEntity) = sessionDao.deleteSession(session)
}
