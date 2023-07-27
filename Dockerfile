# Container image that runs your code
FROM ubuntu
RUN apt-get update
RUN apt-get -y install openjdk-17-jre
RUN curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
RUN apt-get -y install nodejs npm

COPY dist/app.jar /app.jar

COPY run.sh /run.sh
RUN chmod +x /run.sh

ENTRYPOINT /bin/bash /run.sh