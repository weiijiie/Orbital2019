package wjhj.orbital.sportsmatchfindingapp.repo;

import android.net.Uri;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Transaction;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.firestore.auth.User;

import org.imperiumlabs.geofirestore.GeoFirestore;
import org.imperiumlabs.geofirestore.GeoQuery;
import org.imperiumlabs.geofirestore.listeners.GeoQueryDataEventListener;
import org.jetbrains.annotations.NotNull;
import org.threeten.bp.Duration;
import org.threeten.bp.LocalDate;
import org.threeten.bp.LocalTime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Filter;

import java9.util.stream.StreamSupport;
import timber.log.Timber;
import wjhj.orbital.sportsmatchfindingapp.game.Difficulty;
import wjhj.orbital.sportsmatchfindingapp.game.Game;
import wjhj.orbital.sportsmatchfindingapp.game.GameStatus;
import wjhj.orbital.sportsmatchfindingapp.game.Sport;
import wjhj.orbital.sportsmatchfindingapp.game.TimeOfDay;
import wjhj.orbital.sportsmatchfindingapp.user.UserProfile;

public class SportalRepo implements ISportalRepo {
    private static final String DATA_DEBUG = "SportalRepo";
    private static final String USERS_PATH = "Users";
    private static final String GAMES_PATH = "Games";

    private final FirebaseFirestore db;
    private final LoadingCache<String, LiveData<UserProfile>> mUserProfilesCache;
    private final LoadingCache<String, LiveData<Game>> mGamesCache;

    private static volatile SportalRepo instance;

    public static SportalRepo getInstance() {
        if (instance == null) {
            synchronized (SportalRepo.class) {
                if (instance == null) {
                    instance = new SportalRepo();
                }
            }
        }
        return instance;
    }

    private SportalRepo() {
        db = FirebaseFirestore.getInstance();
        mUserProfilesCache = CacheBuilder.newBuilder()
                .maximumSize(100)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(new CacheLoader<String, LiveData<UserProfile>>() {
                    @Override
                    public LiveData<UserProfile> load(@NonNull String key) {
                        DocumentReference ref = db.collection(USERS_PATH).document(key);
                        return Transformations.map(convertToLiveData(ref, UserProfileDataModel.class),
                                SportalRepo.this::toUserProfile);
                    }
                });
        mGamesCache = CacheBuilder.newBuilder()
                .maximumSize(200)
                .expireAfterAccess(15, TimeUnit.MINUTES)
                .build(new CacheLoader<String, LiveData<Game>>() {
                    @Override
                    public LiveData<Game> load(@NonNull String key) {
                        DocumentReference ref = db.collection(GAMES_PATH).document(key);
                        ref.get().addOnSuccessListener(snapshot -> {
                            if (snapshot.exists()) {
                                GameDataModel dataModel = snapshot.toObject(GameDataModel.class);
                                if (dataModel != null && toGame(dataModel).isComplete()) {
                                    changeGameStatus(GameStatus.COMPLETED, dataModel);
                                }
                            }
                        });
                        return Transformations.map(convertToLiveData(ref, GameDataModel.class),
                                SportalRepo.this::toGame);
                    }
                });
    }

    @SuppressWarnings("SameParameterValue")
    private void changeGameStatus(GameStatus status, GameDataModel dataModel) {
        StreamSupport.stream(dataModel.getParticipatingUids())
                .forEach(id -> changeGameStatusForUser(status, dataModel.getUid(), id));
    }

    @Override
    public Task<Void> addUser(String uid, UserProfile userProfile) {
        UserProfileDataModel dataModel = toUserProfileDataModel(userProfile);

        return db.collection(USERS_PATH)
                .document(uid)
                .set(dataModel)
                .addOnSuccessListener(aVoid -> Timber.d("%s added", uid))
                .addOnFailureListener(e -> Timber.d(e, "%s add failed", uid));
    }

    @Override
    public Task<Void> updateUser(String uid, UserProfile userProfile) {
        UserProfileDataModel dataModel = toUserProfileDataModel(userProfile);
        return db.collection(USERS_PATH)
                .document(uid)
                .set(dataModel, SetOptions.merge());
    }

    @Override
    public Task<Void> makeFriendRequest(String senderUid, String receiverUid) {
        WriteBatch batch = db.batch();

        DocumentReference senderDocRef = db.collection(USERS_PATH).document(senderUid);
        batch.update(senderDocRef, "sentFriendRequests", FieldValue.arrayUnion(receiverUid));

        DocumentReference receiverDocRef = db.collection(USERS_PATH).document(receiverUid);
        batch.update(receiverDocRef, "receivedFriendRequests", FieldValue.arrayUnion(senderUid));

        return batch.commit()
                .addOnSuccessListener(aVoid -> Timber.d("Friend request success"))
                .addOnFailureListener(e -> Timber.d(e, "Friend request failed"));
    }

    @Override
    public Task<Void> acceptFriendRequest(String senderUid, String receiverUid) {
        WriteBatch batch = db.batch();

        DocumentReference senderDocRef = db.collection(USERS_PATH).document(senderUid);
        batch.update(senderDocRef, "sentFriendRequests", FieldValue.arrayRemove(receiverUid));
        batch.update(senderDocRef, "friendUids", FieldValue.arrayUnion(receiverUid));

        DocumentReference receiverDocRef = db.collection(USERS_PATH).document(receiverUid);
        batch.update(receiverDocRef, "receivedFriendRequests", FieldValue.arrayRemove(senderUid));
        batch.update(receiverDocRef, "friendUids", FieldValue.arrayUnion(senderUid));

        return batch.commit()
                .addOnSuccessListener(aVoid -> Timber.d("Friend request accept success"))
                .addOnFailureListener(e -> Timber.d(e, "Friend request accept failed"));
    }

    @Override
    public Task<Void> declineFriendRequest(String senderUid, String receiverUid) {
        WriteBatch batch = db.batch();

        DocumentReference senderDocRef = db.collection(USERS_PATH).document(senderUid);
        batch.update(senderDocRef, "sentFriendRequests", FieldValue.arrayRemove(receiverUid));

        DocumentReference receiverDocRef = db.collection(USERS_PATH).document(receiverUid);
        batch.update(receiverDocRef, "receivedFriendRequests", FieldValue.arrayRemove(senderUid));

        return batch.commit()
                .addOnSuccessListener(aVoid -> Timber.d("Friend request decline success"))
                .addOnFailureListener(e -> Timber.d(e, "Friend request decline failed"));

    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public Task<Boolean> isProfileSetUp(String uid) {
        return db.collection(USERS_PATH)
                .document(uid)
                .get()
                .continueWith(task -> task.getResult().exists());
    }

    @Override
    public LiveData<UserProfile> getUser(String userUid) {
        return mUserProfilesCache.getUnchecked(userUid);
    }


    @Override
    public LiveData<List<UserProfile>> selectUsersStartingWith(String field, String queryText) {
        LiveData<List<UserProfileDataModel>> listLiveData = convertToLiveData(
                queryStartingWith(USERS_PATH, field, queryText), UserProfileDataModel.class);

        return Transformations.map(listLiveData, this::toUserProfiles);
    }

    @Override
    public Task<Void> deleteUser(String userUid) {
        throw new UnsupportedOperationException();
    }

    // Should be called when building a game to get the uid for the game.
    @Override
    public String generateGameUid() {
        return db.collection(GAMES_PATH)
                .document()
                .getId();
    }

    @Override
    public Task<Void> addGame(String gameUid, Game game) {
        CollectionReference colRef = db.collection(GAMES_PATH);
        GameDataModel dataModel = toGameDataModel(game);

        WriteBatch batch = db.batch();

        DocumentReference gameDocRef = db.collection(GAMES_PATH).document(gameUid);
        batch.set(gameDocRef, dataModel);

        CollectionReference users = db.collection(USERS_PATH);
        DocumentReference creatorDocRef = users.document(game.getCreatorUid());
        batch.update(creatorDocRef, "games.pending", FieldValue.arrayUnion(gameUid));

        for (String participantUid : game.getParticipatingUids()) {
            DocumentReference participantDocRef = users.document(participantUid);
            batch.update(participantDocRef, "games.pending", FieldValue.arrayUnion(gameUid));
        }

        return batch.commit()
                .addOnSuccessListener(docRef -> {
                    Timber.d("Add game complete.");
                    new GeoFirestore(colRef).setLocation(gameUid, game.getLocation(), Timber::d);
                })
                .addOnFailureListener(e -> Timber.d(e, "Add game failed."));
    }

    @Override
    public Task<Void> updateGame(String gameId, Game game) {
        CollectionReference colRef = db.collection(GAMES_PATH);
        DocumentReference docRef = colRef.document(gameId);
        return db.runTransaction(transaction -> {
            GameDataModel oldRecord = transaction.get(docRef).toObject(GameDataModel.class);

            if (oldRecord != null && oldRecord.getParticipatingUids() != null) {
                Game newGame = game.withParticipatingUids(oldRecord.getParticipatingUids());
                GameDataModel newRecord = toGameDataModel(newGame);
                transaction.set(docRef, newRecord, SetOptions.merge());
            } else {
                GameDataModel newRecord = toGameDataModel(game);
                transaction.set(docRef, newRecord, SetOptions.merge());
            }
            new GeoFirestore(colRef).setLocation(game.getUid(), game.getLocation(), Timber::d);
            return null;
        });
    }

    public Task<Void> addUserToGame(String userId, String gameId) {
        DocumentReference gameDocRef = db.collection(GAMES_PATH).document(gameId);
        DocumentReference userDocRef = db.collection(USERS_PATH).document(userId);
        return db.runTransaction(transaction -> {

            boolean joinedGameSuccessfully = false;
            GameDataModel oldRecord = transaction.get(gameDocRef).toObject(GameDataModel.class);
            UserProfileDataModel user = transaction.get(userDocRef).toObject(UserProfileDataModel.class);

            if (oldRecord != null && oldRecord.getParticipatingUids() != null) {
                List<String> participants = oldRecord.getParticipatingUids();
                participants.add(userId);
                Game newGame = toGame(oldRecord).withParticipatingUids(participants);
                GameDataModel newRecord = toGameDataModel(newGame);
                transaction.set(gameDocRef, newRecord);
                joinedGameSuccessfully = true;

            } else if (oldRecord != null) {
                Game newGame = toGame(oldRecord).withParticipatingUids(userId);
                GameDataModel newRecord = toGameDataModel(newGame);
                transaction.set(gameDocRef, newRecord);
                joinedGameSuccessfully = true;
            }

            if (joinedGameSuccessfully && user != null && user.getGames() != null) {
                Log.d("hi", "hihi");
                Map<String, List<String>> oldGamesList = user.getGames();
                Map<GameStatus, List<String>> newGamesList = new EnumMap<>(GameStatus.class);

                if (oldGamesList.get("confirmed") != null) {
                    newGamesList.put(GameStatus.CONFIRMED, oldGamesList.get("confirmed"));
                } else {
                    newGamesList.put(GameStatus.CONFIRMED, new ArrayList<>());
                }

                if (oldGamesList.get("completed") != null) {
                    newGamesList.put(GameStatus.COMPLETED, oldGamesList.get("completed"));
                } else {
                    newGamesList.put(GameStatus.COMPLETED, new ArrayList<>());
                }

                if (oldGamesList.get("pending") != null) {
                    List<String> pending = oldGamesList.get("pending");
                    pending.add(gameId);
                    newGamesList.put(GameStatus.PENDING, pending);
                } else {
                    List<String> pending = new ArrayList<>();
                    pending.add(gameId);
                    newGamesList.put(GameStatus.PENDING, pending);
                }
                UserProfile updatedUser = toUserProfile(user).withGames(newGamesList);
                transaction.set(userDocRef, toUserProfileDataModel(updatedUser));
            }
            return null;
        });
    }

    public Task<Void> removeUserFromGame(String userId, String gameId) {
        DocumentReference gameDocRef = db.collection(GAMES_PATH).document(gameId);
        DocumentReference userDocRef = db.collection(USERS_PATH).document(userId);

        return db.runTransaction(transaction -> {
            boolean removedSuccessfully = false;
            GameDataModel oldRecord = transaction.get(gameDocRef).toObject(GameDataModel.class);
            UserProfileDataModel user = transaction.get(userDocRef).toObject(UserProfileDataModel.class);

            if (oldRecord != null && oldRecord.getParticipatingUids() != null) {
                List<String> participants = oldRecord.getParticipatingUids();
                participants.remove(userId);
                Game newGame = toGame(oldRecord).withParticipatingUids(participants);
                GameDataModel newRecord = toGameDataModel(newGame);
                transaction.set(gameDocRef, newRecord);
                removedSuccessfully = true;
            }

            if (removedSuccessfully && user != null) {
                Map<String, List<String>> oldGamesList = user.getGames();
                Map<GameStatus, List<String>> newGamesList = new EnumMap<>(GameStatus.class);

                if (oldGamesList.get("confirmed") != null) {
                    List<String> confirmed = oldGamesList.get("confirmed");
                    if (confirmed.contains(gameId)) {
                        confirmed.remove(gameId);
                        newGamesList.put(GameStatus.CONFIRMED, confirmed);
                    } else {
                        newGamesList.put(GameStatus.CONFIRMED, confirmed);
                    }
                } else {
                    newGamesList.put(GameStatus.CONFIRMED, new ArrayList<>());
                }

                if (oldGamesList.get("completed") != null) {
                    List<String> completed = oldGamesList.get("completed");
                    if (completed.contains(gameId)) {
                        completed.remove(gameId);
                        newGamesList.put(GameStatus.COMPLETED, completed);
                    } else {
                        newGamesList.put(GameStatus.COMPLETED, completed);
                    }
                } else {
                    newGamesList.put(GameStatus.COMPLETED, new ArrayList<>());
                }

                if (oldGamesList.get("pending") != null) {
                    List<String> pending = oldGamesList.get("pending");
                    if (pending.contains(gameId)) {
                        pending.remove(gameId);
                        newGamesList.put(GameStatus.PENDING, pending);
                    } else {
                        newGamesList.put(GameStatus.PENDING, pending);
                    }
                } else {
                    newGamesList.put(GameStatus.PENDING, new ArrayList<>());
                }

                UserProfile updatedUser = toUserProfile(user).withGames(newGamesList);
                transaction.set(userDocRef, toUserProfileDataModel(updatedUser));
            }
            return null;
        });
    }

    @Override
    public LiveData<Game> getGame(String gameID) {
        return mGamesCache.getUnchecked(gameID);
    }

    public LiveData<List<UserProfile>> getParticipatingUsers(String gameId) {
        LiveData<List<String>> listOfUserIds =
                Transformations.map(getGame(gameId), Game::getParticipatingUids);

        MediatorLiveData<ConcurrentHashMap<String, UserProfile>> participants = new MediatorLiveData<>();
        ConcurrentHashMap<String, UserProfile> profiles = new ConcurrentHashMap<>();
        participants.setValue(profiles);

        participants.addSource(listOfUserIds, list -> {
            for (String existingUser : profiles.keySet()) {
                if (!list.contains(existingUser)) {
                    profiles.remove(existingUser);
                }
            }

            List<Task<DocumentSnapshot>> tasks = new ArrayList<>();
            for (String uid : list) {
                Task<DocumentSnapshot> task = db.collection(USERS_PATH)
                        .document(uid)
                        .get();
                task.addOnSuccessListener(snapshot -> {
                    UserProfileDataModel profile = snapshot.toObject(UserProfileDataModel.class);
                    if (profile != null) {
                        profiles.put(uid, toUserProfile(profile));
                    } else {
                        Log.d("hi", snapshot.toString());
                    }
                });
                tasks.add(task);
            }

            Tasks.whenAllSuccess(tasks).addOnSuccessListener(results -> participants.setValue(profiles));
        });

        return Transformations.map(participants, x -> new ArrayList<>(x.values()));
    }

    private boolean checkGameWithFilters(Game game, GameSearchFilter filter) {
        List<Sport> sportQuery = Optional.fromNullable(filter.getSportQuery())
                .or(new ArrayList<>());
        List<Difficulty> skillLevelQuery = Optional.fromNullable(filter.getSkillLevelQuery())
                .or(new ArrayList<>());
        List<TimeOfDay> timeOfDayQuery = Optional.fromNullable(filter.getTimeOfDayQuery())
                .or(new ArrayList<>());
        String nameQuery = Optional.fromNullable(filter.getNameQuery()).or("");

        for (Sport sport : sportQuery) {
            for (Difficulty skillLevel : skillLevelQuery) {
                for (TimeOfDay timeOfDay : timeOfDayQuery) {
                    boolean sameSport = sport == game.getSport();
                    boolean sameSkillLevel = skillLevel == game.getSkillLevel();
                    boolean nameMatch = game.getGameName().contains(nameQuery);
                    boolean timeMatch;
                    if (timeOfDay != TimeOfDay.NIGHT) {
                        timeMatch = game.getTime().isAfter(timeOfDay.getStartTime().minusMinutes(1)) &&
                                game.getTime().isBefore((timeOfDay.getEndTime().plusMinutes(1)));
                    } else {
                        timeMatch = (game.getTime().isAfter(timeOfDay.getStartTime().minusMinutes(1)) &&
                                game.getTime().isBefore(LocalTime.of(0, 0))) ||
                                (game.getTime().isAfter(LocalTime.of(23, 59)) &&
                                        game.getTime().isBefore(timeOfDay.getEndTime().plusMinutes(1)));
                    }
                    if (sameSport && sameSkillLevel && nameMatch && timeMatch) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public LiveData<Map<String, Game>> getGamesWithFilters(GameSearchFilter filter) {
        Log.d("hi", "Filtering started...");
        CollectionReference gamesRef = FirebaseFirestore.getInstance().collection("Games");
        GeoFirestore geoFirestore = new GeoFirestore(gamesRef);

        if (filter.hasLocationQuery()) {
            Log.d("hi", "has location");
            Log.d("hi", filter.getLocationQuery().toString());
            GeoQuery locationQuery = geoFirestore
                    .queryAtLocation(filter.getLocationQuery(), filter.getLocationQueryRadius());

            ConcurrentHashMap<String, Game> map = new ConcurrentHashMap<>();
            MutableLiveData<Map<String, Game>> data = new MutableLiveData<>();

            locationQuery.addGeoQueryDataEventListener(new GeoQueryDataEventListener() {
                @Override
                public void onDocumentEntered(@NotNull DocumentSnapshot documentSnapshot, @NotNull GeoPoint geoPoint) {
                    Log.d("hi", "entered");
                    Game game = toGame(documentSnapshot.toObject(GameDataModel.class));
                    map.put(game.getUid(), game);
                    data.setValue(map);
                }

                @Override
                public void onDocumentExited(@NotNull DocumentSnapshot documentSnapshot) {
                    Log.d("hi", "exited");
                }

                @Override
                public void onDocumentMoved(@NotNull DocumentSnapshot documentSnapshot, @NotNull GeoPoint geoPoint) {
                    Log.d("hi", "moved");
                }

                @Override
                public void onDocumentChanged(@NotNull DocumentSnapshot documentSnapshot, @NotNull GeoPoint geoPoint) {
                    Log.d("hi", "changed");
                }

                @Override
                public void onGeoQueryReady() {
                    Log.d("hi", "ready");

                }

                @Override
                public void onGeoQueryError(@NotNull Exception e) {
                    Log.d(DATA_DEBUG, e.toString());
                }
            });
            data.setValue(map);
            return data;

        } else {
            String nameQuery = null;

            ConcurrentHashMap<String, Game> allGames = new ConcurrentHashMap<>();
            List<Task<QuerySnapshot>> tasks = new ArrayList<>();

            if (filter.getNameQuery() != null && filter.getNameQuery().length() > 3) {
                nameQuery = filter.getNameQuery();
            }

            List<Sport> sportsQuery = filter.getSportQuery();
            List<TimeOfDay> timeOfDayQuery = filter.getTimeOfDayQuery();
            if (timeOfDayQuery.isEmpty()) {
                timeOfDayQuery = Arrays.asList(TimeOfDay.MORNING, TimeOfDay.AFTERNOON, TimeOfDay.NIGHT);
            }
            List<Difficulty> skillLevelQuery = filter.getSkillLevelQuery();
            if (skillLevelQuery.isEmpty()) {
                skillLevelQuery =
                        Arrays.asList(Difficulty.BEGINNER, Difficulty.INTERMEDIATE, Difficulty.ADVANCED);
            }

            MutableLiveData<Map<String, Game>> data = new MutableLiveData<>();
            for (Sport sport : sportsQuery) {
                for (TimeOfDay timeOfDay : timeOfDayQuery) {
                    for (Difficulty skillLevel : skillLevelQuery) {
                        Query query = gamesRef.orderBy("time");
                        if (timeOfDay != TimeOfDay.NIGHT) {
                            query = query.startAt(timeOfDay.getStartTime().toString())
                                    .endAt(timeOfDay.getEndTime().toString());
                        } else {
                            query = query.startAt(timeOfDay.getStartTime().toString())
                                    .endAt("23:59");
                        }

                        query = query.whereEqualTo("sport", sport.toString().toUpperCase())
                                .whereEqualTo("skillLevel", skillLevel.toString().toUpperCase())
                                .limit(20);
                        if (nameQuery != null) {
                            query = query.whereArrayContains("nameSubstrings", nameQuery);
                        }
                        Task<QuerySnapshot> querySnapshotTask = query.get();
                        querySnapshotTask.addOnSuccessListener(snapshots -> {
                            StreamSupport.stream(snapshots.getDocuments())
                                    .map(documentSnapshot -> documentSnapshot.toObject(GameDataModel.class))
                                    .map(this::toGame)
                                    .forEach(game -> {
                                        Log.d("hi", game.getGameName() + " 1 " + game.getTime().toString());
                                        allGames.put(game.getUid(), game);
                                        data.setValue(allGames);
                                    });
                        });

                        if (timeOfDay == TimeOfDay.NIGHT) {
                            Query query2 = gamesRef.orderBy("time")
                                    .startAt("00:00")
                                    .endAt(timeOfDay.getEndTime().toString())
                                    .whereEqualTo("sport", sport.toString().toUpperCase())
                                    .whereEqualTo("skillLevel", skillLevel.toString().toUpperCase())
                                    .limit(20);
                            if (nameQuery != null) {
                                query2 = query2.whereArrayContains("nameSubstrings", nameQuery);
                            }
                            Task<QuerySnapshot> querySnapshotTask2 = query2.get();
                            querySnapshotTask2.addOnSuccessListener(snapshots -> {
                                StreamSupport.stream(snapshots.getDocuments())
                                        .map(documentSnapshot -> documentSnapshot.toObject(GameDataModel.class))
                                        .map(this::toGame)
                                        .forEach(game -> {
                                            Log.d("hi", game.getGameName() + " 2 " + game.getTime().toString());
                                            allGames.put(game.getUid(), game);
                                            data.setValue(allGames);
                                        });
                            });
                        }

                        data.setValue(allGames);
                    }
                }
            }

            return data;
        }
    }

    @Override
    public LiveData<List<Game>> selectGamesStartingWith(String field, String queryText) {
        LiveData<List<GameDataModel>> listLiveData = convertToLiveData(
                queryStartingWith("Games", field, queryText), GameDataModel.class);

        return Transformations.map(listLiveData, this::toGames);
    }

    @Override
    public Task<Void> deleteGame(String gameId) {
        return deleteDocument(gameId, "Games");
    }

    // --------------------------------------------------------------------------------------------
    //  *** HELPER METHOD ***
    // --------------------------------------------------------------------------------------------

    public void refreshCache() {
        mUserProfilesCache.invalidateAll();
        mGamesCache.invalidateAll();
    }

    private <T> LiveData<T> convertToLiveData(DocumentReference docRef, Class<T> valueType) {
        MutableLiveData<T> liveData = new MutableLiveData<>();
        docRef.addSnapshotListener((value, err) -> {
            if (err != null) {
                Timber.d(err, "database snapshot error");
            } else if (value != null && value.exists()) {
                try {
                    liveData.postValue(value.toObject(valueType));
                } catch (RuntimeException e) {
                    Timber.d(e, "deserialization error");
                }
            } else {
                Timber.d("document with id %s does not exist", docRef.getId());
            }
        });
        return liveData;
    }

    private <T> LiveData<List<T>> convertToLiveData(Query query, Class<T> valueType) {
        MutableLiveData<List<T>> liveData = new MutableLiveData<>();
        query.addSnapshotListener((value, err) -> {
            if (err != null) {
                Timber.d(err, "database snapshot error");
            } else {
                assert value != null;
                liveData.postValue(value.toObjects(valueType));
            }
        });
        return liveData;
    }

    private Query queryStartingWith(String collection, String field, String queryText) {
        return db.collection(collection)
                .orderBy(field)
                .startAt(queryText)
                .endAt(queryText + "\uf8ff"); // StackOverflow hacks...
    }

    private Task<Void> deleteDocument(String docID, String collectionPath) {
        return db.collection(collectionPath)
                .document(docID)
                .delete()
                .addOnSuccessListener(aVoid -> Timber.d("%s successfully deleted!", docID))
                .addOnFailureListener(e -> Timber.d(e, "Error deleting document"));
    }

    @SuppressWarnings("UnusedReturnValue")
    private Task<UserProfileDataModel> changeGameStatusForUser(GameStatus status, String gameUid, String userUid) {
        return db.runTransaction(transaction -> {
            DocumentReference docRef = db.collection(USERS_PATH).document(userUid);
            UserProfileDataModel dataModel = transaction.get(docRef)
                    .toObject(UserProfileDataModel.class);

            if (dataModel != null) {
                Map<String, List<String>> gamesMap = dataModel.getGames();

                for (Map.Entry<String, List<String>> entry : gamesMap.entrySet()) {
                    if (entry.getKey().equals(status.toString())) {

                        if (!entry.getValue().contains(gameUid)) {
                            entry.getValue().add(gameUid);
                        }

                    } else {
                        entry.getValue().remove(gameUid);
                    }
                }
                transaction.set(docRef, dataModel, SetOptions.merge());
            }
            return dataModel;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // *** METHODS TO CONVERT BETWEEN DOMAIN MODEL AND DATA MODEL ***
    // ---------------------------------------------------------------------------------------------

    private UserProfileDataModel toUserProfileDataModel(UserProfile userProfile) {
        return new UserProfileDataModel(userProfile);
    }

    private UserProfile toUserProfile(UserProfileDataModel dataModel) {
        Map<GameStatus, List<String>> newMap = new HashMap<>();
        Map<String, List<String>> oldMap = dataModel.getGames();
        for (GameStatus gs : GameStatus.values()) {
            List<String> games = oldMap.get(gs.toString());
            if (games != null) {
                newMap.put(gs, games);
            } else {
                newMap.put(gs, new ArrayList<>());
            }
        }

        List<Sport> preferences = orEmptyList(dataModel.getPreferences());
        List<String> friendUids = orEmptyList(dataModel.getFriendUids());
        List<String> sentFriendRequests = orEmptyList(dataModel.getSentFriendRequests());
        List<String> receivedFriendRequests = orEmptyList(dataModel.getReceivedFriendRequests());

        return UserProfile.builder()
                .withDisplayName(dataModel.getDisplayName())
                .withGender(dataModel.getGender())
                .withBirthday(LocalDate.parse(dataModel.getBirthday()))
                .withCountry(dataModel.getCountry())
                .withUid(dataModel.getUid())
                .withDisplayPicUri(Uri.parse(dataModel.getDisplayPicUri()))
                .withBio(Optional.fromNullable(dataModel.getBio()))
                .addAllPreferences(preferences)
                .addAllFriendUids(friendUids)
                .addAllSentFriendRequests(sentFriendRequests)
                .addAllReceivedFriendRequests(receivedFriendRequests)
                .putAllGames(newMap)
                .build();
    }

    private List<UserProfile> toUserProfiles(List<UserProfileDataModel> dataModels) {
        List<UserProfile> newList = new ArrayList<>();
        for (UserProfileDataModel dataModel : dataModels) {
            newList.add(toUserProfile(dataModel));
        }
        return newList;
    }

    private GameDataModel toGameDataModel(Game game) {
        return new GameDataModel(game);
    }

    private Game toGame(GameDataModel dataModel) {

        return Game.builder()
                .withGameName(dataModel.getGameName())
                .withSport(dataModel.getSport())
                .withLocation(dataModel.getLocation())
                .withPlaceName(dataModel.getPlaceName())
                .withMinPlayers(dataModel.getMinPlayers())
                .withMaxPlayers(dataModel.getMaxPlayers())
                .withSkillLevel(dataModel.getSkillLevel())
                .withDate(LocalDate.parse(dataModel.getDate()))
                .withTime(LocalTime.parse(dataModel.getTime()))
                .withDuration(Duration.parse(dataModel.getDuration()))
                .withUid(dataModel.getUid())
                .withCreatorUid(dataModel.getCreatorUid())
                .withDescription(Optional.fromNullable(dataModel.getDescription()))
                .withGameBoardChannelUrl(Optional.fromNullable(dataModel.getGameBoardChannelUrl()))
                .addAllParticipatingUids(dataModel.getParticipatingUids())
                .build();
    }

    private List<Game> toGames(List<GameDataModel> dataModels) {
        List<Game> newList = new ArrayList<>();
        for (GameDataModel dataModel : dataModels) {
            newList.add(toGame(dataModel));
        }
        return newList;
    }

    private <T> List<T> orEmptyList(@Nullable List<T> list) {
        if (list == null) {
            return new ArrayList<>();
        } else {
            return list;
        }
    }
}
