FROM eclipse-temurin:17.0.18_8-jdk-jammy

ARG COMMAND_LINE_TOOLS=11076708
ARG PLATFORM_TOOLS=35.0.2
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/android/.gradle
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL --retry 3 "https://dl.google.com/android/repository/commandlinetools-linux-${COMMAND_LINE_TOOLS}_latest.zip" -o /tmp/cmdline.zip \
    && unzip -q /tmp/cmdline.zip -d /tmp/android \
    && mv /tmp/android/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/cmdline.zip \
    && (yes | sdkmanager --licenses >/dev/null) \
    && sdkmanager --install 'platforms;android-35' 'build-tools;35.0.0' >/dev/null \
    && curl -fsSL --retry 3 "https://dl.google.com/android/repository/platform-tools_r${PLATFORM_TOOLS}-linux.zip" -o /tmp/platform-tools.zip \
    && unzip -oq /tmp/platform-tools.zip -d "$ANDROID_HOME" \
    && rm /tmp/platform-tools.zip \
    && adb version
RUN sdkmanager --install 'platforms;android-36' >/dev/null
RUN sdkmanager --install 'platforms;android-37.1' 'build-tools;37.0.0' >/dev/null
RUN useradd --create-home --uid 1000 android
USER android
ENV HOME=/home/android
WORKDIR /workspace
