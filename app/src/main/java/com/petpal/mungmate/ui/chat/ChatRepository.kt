package com.petpal.mungmate.ui.chat

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.petpal.mungmate.model.ChatRoom
import com.petpal.mungmate.model.FirestoreUserBasicInfoData
import com.petpal.mungmate.model.Message
import com.petpal.mungmate.model.UserReport
import com.petpal.mungmate.model.Match
import com.petpal.mungmate.model.MessageType
import com.petpal.mungmate.model.MessageVisibility
import com.petpal.mungmate.model.PetData
import com.petpal.mungmate.model.UserBasicInfoData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatRepository {
    companion object {
        // collection 이름
        const val CHAT_ROOMS_NAME = "chatRooms"
        const val MESSAGES_NAME = "messages"
        const val MATCHES_NAME = "matches"
        const val REPORTS_NAME = "reports"
        const val USERS_NAME = "users"
        const val PETS_NAME = "pets"

        const val TIMESTAMP = "timestamp"
        const val CHAT_PAGE_SIZE = 100L
    }

    val TAG = "CHAT_REPOSITORY"
    var db = Firebase.firestore

    // todo await() 추가해서 리턴값들 DocumentReference로 통일하기
    // 채팅방에 메시지 추가 = 메시지 전송
    fun saveMessage(chatRoomId: String, message: Message): Task<DocumentReference> {
        var messagesCollection = db.collection(CHAT_ROOMS_NAME)
            .document(chatRoomId)
            .collection(MESSAGES_NAME)
        return messagesCollection.add(message)
    }

    // 변화 감지, 새 메시지 실시간 로드
    fun getMessages(chatRoomId: String): Flow<List<Message>> = callbackFlow {
        // 메시지 리스트
        val messages = mutableListOf<Message>()
        // 채팅방 Collection > 채팅방 Document > 채팅방 내 메시지 Collection 참조
        val messagesRef = db.collection(CHAT_ROOMS_NAME)
            .document(chatRoomId)
            .collection(MESSAGES_NAME)
            .orderBy(TIMESTAMP)

        val listenerRegistration = messagesRef.addSnapshotListener { value, error ->
            if (error != null) {
                return@addSnapshotListener
            }

            messages.clear()
            value?.documents?.forEach { document ->
                val message = document.toObject(Message::class.java)
                message?.let { messages.add(it) }
            }
            // Flow 로 값 보내기
            trySend(messages)
        }

        // Flow 종료 시 리스너 등록 해제
        awaitClose { listenerRegistration.remove() }
    }

    // 산책 매칭 저장
    fun saveMatch(match: Match): Task<DocumentReference> {
        val matchesCollection = db.collection(MATCHES_NAME)
        return matchesCollection.add(match)
    }

    // Document Key 값으로 산책 매칭 데이터 가져오기
    suspend fun getMatchById(matchId: String): DocumentSnapshot? {
        return try {
            db.collection(MATCHES_NAME)
                .document(matchId)
                .get()
                .await()
        } catch (e: Exception) {
            null
        }
    }

    // 사용자 id로 사용자 Document 가져오기
    suspend fun getUserBasicInfoById(userId: String): FirestoreUserBasicInfoData? {
        return try {
            db.collection(USERS_NAME).document(userId).get().await().toObject(FirestoreUserBasicInfoData::class.java)
        } catch (e: Exception) {
            Log.d(TAG, "getUserBasicInfo failed : ${e.printStackTrace()}")
            null
        }
    }

    suspend fun getMainPetInfoByUserId(userId: String): PetData? {
        return try {
            // 대표 반려견으로 첫 번째 Document 가져오기
            val collectionRef = db.collection(USERS_NAME).document(userId).collection(PETS_NAME)
            val petDocument = collectionRef.limit(1).get().await().documents.firstOrNull()
            petDocument?.toObject(PetData::class.java)
        }catch (e: Exception) {
            Log.d(TAG, "getMainPetInfoByUserId failed : ${e.printStackTrace()}")
            null
        }
    }

    // 사용자 신고
    fun saveUserReport(userReport: UserReport): Task<DocumentReference> {
        val userReportsCollection = db.collection(REPORTS_NAME)
        return userReportsCollection.add(userReport)
    }

    // matches 컬렉션 내 문서의 특정 필드 업데이트
    suspend fun updateFieldInMatchDocument(matchKey: String, fieldName: String, updateValue: Any) {
        val matchDocumentRef = db.collection(MATCHES_NAME).document(matchKey)
        val updateData = hashMapOf<String, Any>()
        updateData[fieldName] = updateValue

        try {
            matchDocumentRef.update(updateData).await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getOrCreateChatRoom(user1Id: String, user2Id: String): String {
        // 사용자 ID 조합으로 chatroom Key 생성
        val chatRoomKey = listOf(user1Id, user2Id).sorted().joinToString("_")

        // chatroom 존재 확인
        val chatRoomDocRef = db.collection(CHAT_ROOMS_NAME).document(chatRoomKey)
        val chatRoomDocSnapshot = chatRoomDocRef.get().await()

        if (chatRoomDocSnapshot.exists()) {
            // 채팅방이 이미 존재하는 경우, 기존 채팅방의 ID를 반환
            Log.d(TAG, "load chatroom completed")
            return chatRoomKey
        } else {
            // 채팅방이 존재하지 않는 경우, 새로운 채팅방을 생성하고 ID를 반환
            val newChatRoom = ChatRoom(
                user1Id,
                user2Id,
                "",
                Timestamp.now(),
                false,
                false,
                0,
                0
            )
            chatRoomDocRef.set(newChatRoom).await()
            Log.d(TAG, "create chatroom completed")

            // 최초 메시지로 오늘 DATE 전송
            val dateContent = SimpleDateFormat("yyyy년 MM월 dd일", Locale.getDefault())
                .format(Date())
            val message = Message(
                user1Id,
                dateContent,
                Timestamp.now(),
                true,
                MessageType.DATE.code,
                MessageVisibility.ALL.code
            )
            saveMessage(chatRoomKey, message)
            Log.d(TAG, "send date message completed")

            return chatRoomKey
        }
    }


    // 채팅방의 모든 메시지 로드
//    fun getSavedMessages(chatRoomId: String): CollectionReference {
//        val messagesCollection = db.collection("${CHAT_ROOMS_NAME}/${chatRoomId}/${MESSAGES_NAME}")
//        return messagesCollection
//    }

    // 현재 시간보다 이전 메시지를 N개 가져오는데 사용, Paging으로 자르기
//    suspend fun receiveMessages(chatRoomId: String, lastTime: Timestamp): List<Message> {
//        return db.collection(CHAT_ROOMS_NAME)
//            .document(chatRoomId)
//            .collection(MESSAGES_NAME)
//            .whereLessThan(TIMESTAMP, lastTime)
//            .orderBy(TIMESTAMP, Query.Direction.DESCENDING)
//            .limit(CHAT_PAGE_SIZE)
//            .get()
//            .await()
//            .documents
//            .map { document ->
//                document.let {
//                    document.toObject(Message::class.java)
//                }?: kotlin.run {
//                    Message()
//                }
//            }
//    }
}