package wjhj.orbital.sportsmatchfindingapp.homepage.socialpage;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import java.util.List;

import wjhj.orbital.sportsmatchfindingapp.game.Sport;
import wjhj.orbital.sportsmatchfindingapp.repo.SportalRepo;
import wjhj.orbital.sportsmatchfindingapp.user.UserProfile;
import wjhj.orbital.sportsmatchfindingapp.utils.BatchTransformations;

public class SocialFriendsViewModel extends ViewModel {

    private final SportalRepo repo;

    private LiveData<UserProfile> currUser;
    private LiveData<List<UserProfile>> friends;

    public SocialFriendsViewModel(String userUid) {
        repo = SportalRepo.getInstance();
        currUser = repo.getUser(userUid);
        LiveData<List<String>> friendUids = Transformations.map(currUser, UserProfile::getFriendUids);
        friends = BatchTransformations.switchMapList(friendUids, repo::getUser, UserProfile::getUid);
    }

    LiveData<List<UserProfile>> getFriends() {
        return friends;
    }

    Task<List<Sport>> getCurrUserPreferencesTask() {
        TaskCompletionSource<List<Sport>> taskCompletionSource = new TaskCompletionSource<>();
        currUser.observeForever(new Observer<UserProfile>() {
            @Override
            public void onChanged(UserProfile userProfile) {
                taskCompletionSource.setResult(userProfile.getPreferences());
                currUser.removeObserver(this);
            }
        });
        return taskCompletionSource.getTask();
    }

}
