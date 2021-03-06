package wjhj.orbital.sportsmatchfindingapp.user;

import android.net.Uri;
import android.util.Log;
import android.widget.RadioGroup;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;


import com.google.common.base.Optional;

import org.threeten.bp.LocalDate;

import java.util.ArrayList;
import java.util.List;

import java9.util.stream.StreamSupport;
import timber.log.Timber;
import wjhj.orbital.sportsmatchfindingapp.R;
import wjhj.orbital.sportsmatchfindingapp.auth.Authentications;
import wjhj.orbital.sportsmatchfindingapp.game.Sport;
import wjhj.orbital.sportsmatchfindingapp.maps.Country;
import wjhj.orbital.sportsmatchfindingapp.repo.SportalRepo;
import wjhj.orbital.sportsmatchfindingapp.utils.ValidationInput;

public class UserPreferencesViewModel extends ViewModel {

    private UserProfile editProfile;
    private MutableLiveData<Uri> displayPicUri;
    private MutableLiveData<String> bio;
    private ValidationInput<LocalDate> birthday;
    private ValidationInput<Country> country;
    private ValidationInput<Gender> gender;
    private MutableLiveData<Boolean> success;
    private MutableLiveData<List<Sport>> sportPreferences;

    private List<ValidationInput<?>> validationsList;
    private SportalRepo repo;

    public UserPreferencesViewModel() {
        repo = SportalRepo.getInstance();

        displayPicUri = new MutableLiveData<>();
        bio = new MutableLiveData<>();
        birthday = new ValidationInput<>(date -> date != null && !date.isAfter(LocalDate.now()),
                "Please enter a valid birthday");
        country = new ValidationInput<>(country -> country != null, "Please select a country");
        gender = new ValidationInput<>(gender -> gender != null, "");
        sportPreferences = new MutableLiveData<>();
        success = new MutableLiveData<>();
        validationsList = new ArrayList<>();
        validationsList.add(birthday);
        validationsList.add(country);
        validationsList.add(gender);
    }

    public LiveData<Uri> getDisplayPicUri() {
        return displayPicUri;
    }

    public void setDisplayPicUri(Uri displayPicUri) {
        this.displayPicUri.setValue(displayPicUri);
    }

    public MutableLiveData<String> getBio() {
        return bio;
    }

    public void onGenderChanged(RadioGroup radioGroup, int id) {
        switch (id) {
            case R.id.male:
                gender.setInput(Gender.MALE);
                break;
            case R.id.female:
                gender.setInput(Gender.FEMALE);
                break;
        }
    }

    public ValidationInput<LocalDate> getBirthday() {
        return this.birthday;
    }

    void setBirthday(LocalDate date) {
        this.birthday.setInput(date);
    }

    public ValidationInput<Country> getCountry() {
        return this.country;
    }

    public void setCountry(Country country) {
        this.country.setInput(country);
    }

    public ValidationInput<Gender> getGender() {
        return this.gender;
    }

    LiveData<List<Sport>> getSportPreferences() {
        return sportPreferences;
    }

    private void setSportPreferences(List<Sport> sportPreferences) {
        this.sportPreferences.setValue(sportPreferences);
    }

    void updateSportPreferences(boolean[] sportSelections) {
        List<Sport> sportsPreferences = sportPreferences.getValue();
        if (sportsPreferences == null) {
            sportsPreferences = new ArrayList<>();
        }

        for (int i = 0; i < sportSelections.length; i++) {
            Sport sport = Sport.values()[i];
            boolean selected = sportSelections[i];
            boolean contains = sportsPreferences.contains(sport);
            if (selected && !contains) {
                sportsPreferences.add(sport);
            } else if (!selected && contains) {
                sportsPreferences.remove(sport);
            }
        }

        setSportPreferences(sportsPreferences);
    }

    boolean[] getSportSelections() {
        boolean[] selections = new boolean[Sport.values().length];
        List<Sport> currList = sportPreferences.getValue();
        if (currList != null) {
            for (Sport sport : currList) {
                selections[sport.ordinal()] = true;
            }
        }

        return selections;
    }

    MutableLiveData<Boolean> getSuccess() {
        return success;
    }


    void linkWithExistingProfile(String uid) {
        LiveData<UserProfile> existingProfile = repo.getUser(uid);

        existingProfile.observeForever(new Observer<UserProfile>() {
            @Override
            public void onChanged(UserProfile profile) {
                editProfile = profile;
                displayPicUri.postValue(profile.getDisplayPicUri());
                bio.postValue(profile.getBio().or(""));
                birthday.setInput(profile.getBirthday());
                country.setInput(profile.getCountry());
                gender.setInput(profile.getGender());
                sportPreferences.postValue(new ArrayList<>(profile.getPreferences()));

                existingProfile.removeObserver(this);
            }
        });
    }

    void updateProfile(String displayName, String currUserUid) {
        StreamSupport.stream(validationsList).forEach(ValidationInput::validate);

        if (StreamSupport.stream(validationsList)
                .allMatch(input -> input.getState() == ValidationInput.State.VALIDATED)) {

            List<Sport> preferences = sportPreferences.getValue() == null
                    ? new ArrayList<>()
                    : sportPreferences.getValue();


            UserProfile userProfile;

            if (editProfile != null) {
                userProfile = editProfile.withGender(gender.getInput())
                        .withBirthday(birthday.getInput())
                        .withCountry(country.getInput())
                        .withBio(Optional.fromNullable(bio.getValue()))
                        .withPreferences(preferences);
                repo.updateUser(currUserUid, userProfile);
            } else {
                userProfile = UserProfile.builder()
                        .withDisplayName(displayName)
                        .withGender(gender.getInput())
                        .withBirthday(birthday.getInput())
                        .withCountry(country.getInput())
                        .withUid(currUserUid)
                        .withBio(Optional.fromNullable(bio.getValue()))
                        .addAllPreferences(preferences)
                        .build();
                repo.addUser(currUserUid, userProfile);
            }


            Uri selectedUri = displayPicUri.getValue();
            if (selectedUri != null) {
                Authentications auths = new Authentications();
                auths.uploadDisplayImageAndGetUri(selectedUri, currUserUid)
                        .addOnSuccessListener(uri ->
                                repo.updateUser(currUserUid, userProfile.withDisplayPicUri(uri)))
                        .addOnFailureListener(e ->
                                Timber.d(e, "profile pic upload failed"));
            }

            success.setValue(true);
        }
    }

}
