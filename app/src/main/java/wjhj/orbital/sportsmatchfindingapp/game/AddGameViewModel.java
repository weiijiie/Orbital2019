package wjhj.orbital.sportsmatchfindingapp.game;


import androidx.databinding.ObservableInt;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.google.android.gms.tasks.Task;
import com.google.common.base.Optional;
import com.google.firebase.firestore.GeoPoint;
import com.sendbird.android.GroupChannel;

import org.threeten.bp.Duration;
import org.threeten.bp.LocalDate;
import org.threeten.bp.LocalTime;

import java.util.ArrayList;
import java.util.List;

import java9.util.stream.StreamSupport;
import timber.log.Timber;
import wjhj.orbital.sportsmatchfindingapp.messaging.SendBirdConnectionManager;
import wjhj.orbital.sportsmatchfindingapp.repo.SportalRepo;
import wjhj.orbital.sportsmatchfindingapp.utils.Result;
import wjhj.orbital.sportsmatchfindingapp.utils.ValidationInput;


public class AddGameViewModel extends ViewModel {

    private ObservableInt sportSelection;
    private ValidationInput<String> gameName;
    private ValidationInput<LocalDate> date;
    private ValidationInput<LocalTime> time;
    private ValidationInput<Duration> duration;
    private GeoPoint locationPoint;
    private ValidationInput<String> placeName;
    private ValidationInput<String> minPlayersInput;
    private ValidationInput<String> maxPlayersInput;
    private ValidationInput<Difficulty> skillLevel;
    private ValidationInput<String> gameDescription;
    private MutableLiveData<Result<Game>> newGameResult;

    private List<ValidationInput<?>> validations;
    private SportalRepo repo;

    private Game editGame;

    public AddGameViewModel() {
        sportSelection = new ObservableInt();
        gameName = new ValidationInput<>(text -> text != null && !text.equals(""), "Name cannot be blank.");
        date = new ValidationInput<>(date -> date != null && !date.isBefore(LocalDate.now()), "");
        time = new ValidationInput<>(time -> time != null, "");
        duration = new ValidationInput<>(duration -> duration != null, "");
        placeName = new ValidationInput<>(text -> text != null && !text.equals(""), "");
        minPlayersInput = new ValidationInput<>(num -> num != null && !num.equals("0"), "Enter valid number.");
        maxPlayersInput = new ValidationInput<>(num -> num != null && !num.equals("0"), "Enter valid number.");
        skillLevel = new ValidationInput<>(difficulty -> difficulty != null, "");
        gameDescription = new ValidationInput<>(text -> text == null || text.length() <= 250, "");
        newGameResult = new MutableLiveData<>();

        validations = new ArrayList<>();
        validations.add(gameName);
        validations.add(date);
        validations.add(time);
        validations.add(duration);
        validations.add(placeName);
        validations.add(minPlayersInput);
        validations.add(maxPlayersInput);
        validations.add(skillLevel);
        validations.add(gameDescription);

        repo = SportalRepo.getInstance();
    }

    public ObservableInt getSportSelection() {
        return sportSelection;
    }

    public ValidationInput getGameName() {
        return gameName;
    }

    public ValidationInput<LocalDate> getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date.setInput(date);
    }

    public ValidationInput<LocalTime> getTime() {
        return time;
    }

    public void setTime(LocalTime time) {
        this.time.setInput(time);
    }

    public ValidationInput<Duration> getDuration() {
        return duration;
    }

    public void setDuration(Duration duration) {
        this.duration.setInput(duration);
    }

    public String toDurationString(Duration duration) {
        if (duration == null) {
            return null;
        }
        long hours = duration.getSeconds() / 3600;
        long minutes = (duration.getSeconds() % 3600) / 60;
        return hours + " h  " + minutes + " m";
    }

    public void setLocationPoint(GeoPoint locationPoint) {
        this.locationPoint = locationPoint;
    }

    public ValidationInput<String> getPlaceName() {
        return placeName;
    }

    public ValidationInput<String> getMinPlayersInput() {
        return minPlayersInput;
    }

    public ValidationInput<String> getMaxPlayersInput() {
        return maxPlayersInput;
    }

    public void setPlaceName(String placeName) {
        this.placeName.setInput(placeName);
    }

    public ValidationInput<Difficulty> getSkillLevel() {
        return skillLevel;
    }

    public void setSkillLevel(Difficulty skillLevel) {
        this.skillLevel.setInput(skillLevel);
    }

    public ValidationInput<String> getGameDescription() {
        return gameDescription;
    }

    public LiveData<Result<Game>> getNewGameResult() {
        return newGameResult;
    }

    public void makeGame(String creatorUid) {
        StreamSupport.stream(validations).forEach(ValidationInput::validate);

        if (StreamSupport.stream(validations)
                .allMatch(input -> input.getState() == ValidationInput.State.VALIDATED)) {

            if (Integer.valueOf(maxPlayersInput.getInput()) < Integer.valueOf(minPlayersInput.getInput())) {
                maxPlayersInput.setState(ValidationInput.State.ERROR);
                return;
            }

            Game game;
            if (editGame == null) {
                String newGameUid = repo.generateGameUid();

                game = Game.builder()
                        .withGameName(gameName.getInput())
                        .withSport(Sport.values()[sportSelection.get()])
                        .withLocation(locationPoint)
                        .withPlaceName(placeName.getInput())
                        .withMinPlayers(Integer.valueOf(minPlayersInput.getInput()))
                        .withMaxPlayers(Integer.valueOf(maxPlayersInput.getInput()))
                        .withSkillLevel(skillLevel.getInput())
                        .withDate(date.getInput())
                        .withTime(time.getInput())
                        .withDuration(duration.getInput())
                        .withUid(newGameUid)
                        .withCreatorUid(creatorUid)
                        .withDescription(Optional.fromNullable(gameDescription.getInput()))
                        .addParticipatingUid(creatorUid)
                        .build();

                repo.addGame(newGameUid, game)
                        .addOnSuccessListener(aVoid -> newGameResult.postValue(new Result<>(game)))
                        .addOnFailureListener(e -> newGameResult.postValue(new Result<>(e)));

                SendBirdConnectionManager.createGameBoardChannel(newGameUid)
                        .addOnSuccessListener(groupChannel ->
                                repo.updateGame(game.getUid(),
                                        game.withGameBoardChannelUrl(groupChannel.getUrl())))
                        .addOnFailureListener(e -> Timber.d(e, "Create group channel failure"));
            } else {

                game = editGame.withGameName(gameName.getInput())
                        .withSport(Sport.values()[sportSelection.get()])
                        .withLocation(locationPoint)
                        .withPlaceName(placeName.getInput())
                        .withMinPlayers(Integer.valueOf(minPlayersInput.getInput()))
                        .withMaxPlayers(Integer.valueOf(maxPlayersInput.getInput()))
                        .withSkillLevel(skillLevel.getInput())
                        .withDate(date.getInput())
                        .withTime(time.getInput())
                        .withDuration(duration.getInput())
                        .withCreatorUid(creatorUid)
                        .withDescription(Optional.fromNullable(gameDescription.getInput()));

                repo.updateGame(editGame.getUid(), game)
                        .addOnSuccessListener(aVoid -> newGameResult.postValue(new Result<>(game)))
                        .addOnFailureListener(e -> newGameResult.postValue(new Result<>(e)));
            }
        }
    }

    void setExistingGame(Game gameData) {
        editGame = gameData;
        sportSelection.set(gameData.getSport().ordinal());
        gameName.setInput(gameData.getGameName());
        date.setInput(gameData.getDate());
        time.setInput(gameData.getTime());
        duration.setInput(gameData.getDuration());
        locationPoint = gameData.getLocation();
        placeName.setInput(gameData.getPlaceName());
        minPlayersInput.setInput(String.valueOf(gameData.getMinPlayers()));
        maxPlayersInput.setInput(String.valueOf(gameData.getMaxPlayers()));
        skillLevel.setInput(gameData.getSkillLevel());
        gameDescription.setInput(gameData.getDescription().orNull());
    }
}
