FROM debian:trixie-slim@sha256:8aa5a7bf5a1955bddbe7d74fcdd0b91ab30e74362a8b73af672b0481b35b44a0 AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl make meson ninja-build nasm pkg-config unzip xz-utils zip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fLsS --retry 3 https://dl.google.com/android/repository/android-ndk-r30-linux.zip -o ndk.zip \
    && echo '5107f898313790e449e87eee2183d9a20602dee9  ndk.zip' | sha1sum -c - \
    && unzip -q ndk.zip 'android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/*' && rm ndk.zip
ENV PATH="/src/android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin:${PATH}"
ADD --checksum=sha256:1a4d394ae63d190a526cd052eb4415c156b5c5e2df37efab41a7de0b8fde2433 https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz ffmpeg.tar.xz
ADD --checksum=sha256:ca96b1d0ba3f01f55de7231d775892b64994ba061aaeeaaa4f662a390b3abf9e https://download.videolan.org/pub/videolan/dav1d/1.5.5/dav1d-1.5.5.tar.xz dav1d.tar.xz
ADD --checksum=sha256:6683ba04b2a1076e5cc21194df5233f63239ae76986f261955379d60efe05948 https://codeload.github.com/anilbeesetti/nextlib/tar.gz/539411ccb4740251252a2cc8896c6579d0c43c3d nextlib.tar.gz
RUN mkdir ffmpeg dav1d nextlib && tar xf ffmpeg.tar.xz -C ffmpeg --strip-components=1 \
    && tar xf dav1d.tar.xz -C dav1d --strip-components=1 && tar xf nextlib.tar.gz -C nextlib --strip-components=1
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY build-abi /src/
FROM build AS arm64
RUN sh build-abi arm64-v8a aarch64 aarch64-linux-android
FROM build AS arm
RUN sh build-abi armeabi-v7a arm armv7a-linux-androideabi
FROM build AS amd64
RUN sh build-abi x86_64 x86_64 x86_64-linux-android
FROM build AS x86
RUN sh build-abi x86 x86 i686-linux-android --disable-asm

FROM build AS bindings
ADD --checksum=sha256:f397df4e6383ff282f9037a91c981f89c10e015c82a76ff9f3b524373add1dd7 https://repo.maven.apache.org/maven2/io/github/anilbeesetti/nextlib-media3ext/1.11.1-0.19.0/nextlib-media3ext-1.11.1-0.19.0.aar nextlib.aar
RUN mkdir -p /bindings/assets/licenses && cp dav1d/COPYING /bindings/assets/licenses/dav1d.txt \
    && unzip -p nextlib.aar classes.jar > classes.jar \
    && unzip -q classes.jar '*/FfmpegVideo*.class' '*/FfmpegAudio*.class' '*/FfmpegLibrary*.class' \
        '*/DrainingDecoder*.class' '*/FfmpegDecoderException.class' \
    && zip -qr /bindings/nextlib-decoder.jar io

FROM scratch
COPY --from=bindings /bindings/ /
COPY --from=arm64 /out/jni/ /jni/
COPY --from=arm /out/jni/ /jni/
COPY --from=amd64 /out/jni/ /jni/
COPY --from=x86 /out/jni/ /jni/
