package guice;

import com.google.inject.AbstractModule;
import controller.ChatFunctionalities;
import controller.LoginFunctionalities;
import controller.ScreenFunctionalities;
import controller.impl.ChatController;
import controller.impl.LoginController;
import controller.impl.ScreenController;
import network.InputStreamReader;
import network.ServerServices;
import network.impl.InputStreamReaderImpl;
import network.impl.ServerServicesImpl;
import util.image.ScreenLiveStream;
import util.image.impl.ScreenLiveStreamImpl;
import util.voice.VoicePlayback;
import util.voice.VoiceRecorder;
import util.voice.impl.VoicePlaybackImpl;
import util.voice.impl.VoiceRecorderImpl;
import view.ChatView;
import view.LoginView;
import view.ScreenView;
import view.impl.ChatViewImpl;
import view.impl.LoginViewImpl;
import view.impl.ScreenViewImpl;

public class GuiceModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(LoginView.class).to(LoginViewImpl.class).asEagerSingleton();
        bind(ChatView.class).to(ChatViewImpl.class).asEagerSingleton();
        bind(ScreenView.class).to(ScreenViewImpl.class).asEagerSingleton();

        bind(LoginFunctionalities.class).to(LoginController.class).asEagerSingleton();
        bind(ChatFunctionalities.class).to(ChatController.class).asEagerSingleton();
        bind(ScreenFunctionalities.class).to(ScreenController.class).asEagerSingleton();

        bind(InputStreamReader.class).to(InputStreamReaderImpl.class).asEagerSingleton();
        bind(ServerServices.class).to(ServerServicesImpl.class).asEagerSingleton();

        bind(VoiceRecorder.class).to(VoiceRecorderImpl.class);
        bind(VoicePlayback.class).to(VoicePlaybackImpl.class);

        bind(ScreenLiveStream.class).to(ScreenLiveStreamImpl.class);
    }
}
