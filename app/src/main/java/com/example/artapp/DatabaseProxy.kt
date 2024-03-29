 package com.example.artapp

import android.util.Log
import com.google.firebase.database.*

// `object` is used here to make the DatabaseProxy a singleton
object DatabaseProxy {

    private val mDBRef : DatabaseReference = FirebaseDatabase.getInstance().reference
    private val GLOBAL_ROOM_PATH = "globalRoom"
    private val PRIVATE_ROOMS_PATH = "privateRooms"
    private val ROOM_KEY_RANGE = 3..7
    private lateinit var mUserKey : String
    private lateinit var mUserRef : DatabaseReference
    public var mPaintView : PaintView? = null
    public lateinit var mMainActivity: MainActivity
    public var mWidth: Int = 0
    public var mHeight : Int = 0
    public var mDBcurrRoomRef : DatabaseReference = mDBRef
    public var mDBparentRef : DatabaseReference = mDBRef
    public var mDBprivateRoomRef : DatabaseReference = mDBRef
    private lateinit var mUpdateViewListener : ValueEventListener
    private lateinit var mRealtimeListener : ChildEventListener
    private lateinit var mPrivateRoomListener : ValueEventListener


    init {
        // in case we need to initialize anything
    }

    // sets up the DB ref and returns a new user key if parameter is null
    fun enterGlobalRoom(userKey:String?) : String {
        if (userKey == null) { // user hasn't entered the room yet
            mUserRef = mDBRef.child(GLOBAL_ROOM_PATH).push().ref // create user in global room
            setUpUserEntry()
        } else { // user has already been in the room
            mUserRef = mDBRef.child(GLOBAL_ROOM_PATH).child(userKey).ref // get reference
        }

        mUserKey = mUserRef.key!! // save userKey
        setEventListeners()

        return mUserKey
    }

    // makes an asynchronous check to see if room exists, then calls the handler in MainActivity
    fun requestToEnterExistingRoom(userKey:String?, roomKey : String) {

        mDBprivateRoomRef = mDBRef.child(PRIVATE_ROOMS_PATH)

        mPrivateRoomListener = object : ValueEventListener {

            override fun onDataChange(privateRoomsSnapshot: DataSnapshot) {
                var privateRoomRef : DatabaseReference? = null
                var responsePair : Pair<String, String>? = null
                // search for a matching room key
                for (privateRoom in privateRoomsSnapshot.children) {
                    if (privateRoom.key!!.substring(ROOM_KEY_RANGE) == roomKey) { // roomKey matched
                        privateRoomRef = privateRoomsSnapshot.ref.child(privateRoom.key!!).ref
                        if (userKey == null) { // user does not have a userKey
                            // create user inside private room
                            mUserRef = privateRoomRef.push().ref
                            setUpUserEntry()
                        } else {               // user already has a userKey
                            mUserRef = privateRoomRef.child(userKey).ref // get reference
                        }
                        mUserKey = mUserRef.key!! // save userKey
                        setEventListeners()

                        responsePair = Pair(mUserKey, roomKey) // prepare response tuple
                    }
                }

                // respond to MainActivity's request
                mMainActivity.finishExistingRoomRequest(responsePair)
            }

            // called when listener failed at server or was removed due to Firebase security rules
            override fun onCancelled(p0: DatabaseError) {}
        }
        mDBprivateRoomRef.addListenerForSingleValueEvent(mPrivateRoomListener)
    }

    // sets up the DB ref and returns a new user key if parameter is null
    fun enterNewRoom(userKey:String?) : Pair<String, String> {
        if (userKey == null) { // user does not have a userKey
            // create private room and user
            mUserRef = mDBRef.child(PRIVATE_ROOMS_PATH).push().push().ref
        } else { // user already has a userKey
            mUserRef = mDBRef.child(PRIVATE_ROOMS_PATH).push().child(userKey).ref // get reference
        }

        mUserKey = mUserRef.key!! // save userKey
        setUpUserEntry()
        setEventListeners()

        val roomKey = mUserRef.parent!!.key!!.substring(ROOM_KEY_RANGE) // a substring is taken
        return Pair(mUserKey, roomKey)
    }

    // Initializes the users entry with an empty line object
    private fun setUpUserEntry() {
        mUserRef.setValue(PaintView.Line())
    }

    // Adds event listeners to listen to firebase for drawings from other users
    private fun setEventListeners() {
        mRealtimeListener = object : ChildEventListener {

            override fun onChildChanged(otherUser: DataSnapshot, previousChildName: String?) {
                if (otherUser.key != mUserKey && otherUser.key != "list") {
                    // get the line obj from the database and draw it
                    val line: PaintView.Line? = otherUser.getValue(PaintView.Line::class.java)

                    if (line != null && mPaintView != null) {
                        mPaintView!!.drawLine(line)
                    }
                }
            }

            override fun onChildAdded(otherUser: DataSnapshot, previousChildName: String?) {
                if (otherUser.key != mUserKey) {
                    // get the line obj from the database and draw it
                    val line: PaintView.Line? = otherUser.getValue(PaintView.Line::class.java)
                    if (line != null && mPaintView != null) {
                        mPaintView!!.drawLine(line)
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                // probably don't need to use this method
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                // probably don't need to use this method
            }

            override fun onCancelled(error: DatabaseError) {
                // probably don't need to use this method
            }
        }
        mDBparentRef = mUserRef.parent!!
        mDBparentRef.addChildEventListener(mRealtimeListener)
    }

    // writes user's last drawing/scribble to the appropriate "room" on firebase
    fun sendLineToDatabase(line: PaintView.Line) {
        var currRoom = mUserRef.parent!!.key!!

        // Adds line to a list in the database containing every drawn line
        if(currRoom == GLOBAL_ROOM_PATH) { // User is in global room
            var ref = mDBRef.child(GLOBAL_ROOM_PATH).child("list").push()
            ref.setValue(line)
        } else { // User is in a private room
            var ref = mDBRef.child(PRIVATE_ROOMS_PATH).child(currRoom).child("list").push()
            ref.setValue(line)
        }

        // Sets the current users reference in the database to this line object
        mUserRef.setValue(line)
                .addOnSuccessListener { Log.i("line", "Line successfully written!") }
                .addOnFailureListener { e -> Log.i("line", "Error writing line :(", e) }

    }

    // Pulls already drawn lines from database and draws them on the current canvas
    fun updateView() {
        var currRoom = mUserRef.parent!!.key!!

        // Sets reference to the current room
        mDBcurrRoomRef = if(currRoom == GLOBAL_ROOM_PATH) {
            mDBRef.child(GLOBAL_ROOM_PATH).child("list")
        } else {
            mDBRef.child(PRIVATE_ROOMS_PATH).child(currRoom).child("list")
        }
        var list2 = ArrayList<PaintView.Line>()
        mUpdateViewListener = object : ValueEventListener {
            override fun onCancelled(p0: DatabaseError) {
            }

            override fun onDataChange(data: DataSnapshot) {
                for(i in data.children) {
                    var line = i.getValue(PaintView.Line::class.java)
                    list2.add(line!!)  // Adds current snapshot to a list
                }
                for(y in list2) {  // Loops through list and draws each line
                    if (y != null && mPaintView != null) {
                        mPaintView!!.drawLine(y)
                    }
                }
            }
        }
        mDBcurrRoomRef.addValueEventListener(mUpdateViewListener)
    }

    // Removes event listeners on firebase
    fun removeListeners() {
        if(mDBcurrRoomRef != null) {
            mDBcurrRoomRef.removeEventListener(mUpdateViewListener)
        }
        if(mDBparentRef != null) {
            mDBparentRef.removeEventListener(mRealtimeListener)
        }
        if(mDBprivateRoomRef != null && mDBprivateRoomRef != mDBRef) {
            mDBprivateRoomRef.removeEventListener(mPrivateRoomListener)
        }
    }

}