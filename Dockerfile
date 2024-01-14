ARG NETSHOT_VERSION=0.0.1-dev
ARG GRAALVM_VERSION=17.0.8

FROM debian:12 AS debian-graalvm
ARG GRAALVM_VERSION
RUN apt-get -y update && apt-get -y install wget
WORKDIR /usr/lib/jvm
RUN wget --quiet https://download.oracle.com/graalvm/${GRAALVM_VERSION%%.*}/archive/graalvm-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz && \ 
    tar xvzf graalvm-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz && \
    rm -f graalvm-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz && \
    ln -sfn graalvm-jdk-${GRAALVM_VERSION}* graalvm && \
    update-alternatives --install /usr/bin/java java /usr/lib/jvm/graalvm/bin/java 92100
RUN /usr/lib/jvm/graalvm/bin/gu install js python


FROM debian-graalvm AS builder
ARG NETSHOT_VERSION
COPY . /build
WORKDIR /build
RUN echo $NE
RUN sed -i -r "s/VERSION = \".*\";/VERSION = \"$NETSHOT_VERSION\";/g" \
       src/main/java/onl/netfishers/netshot/Netshot.java
RUN ./mvnw package


FROM debian-graalvm
RUN mkdir /usr/local/netshot /var/log/netshot
COPY --from=builder /build/target/netshot.jar /usr/local/netshot/netshot.jar
COPY dist/netshot.conf.docker /etc/netshot.conf
EXPOSE 8080
CMD ["/usr/bin/java", "-jar", "/usr/local/netshot/netshot.jar"]
