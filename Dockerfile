# Container image that runs your code
FROM ubuntu
RUN apt-get update
RUN apt-get -y install openjdk-17-jre
RUN curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.3/install.sh | bash
RUN source ~/.bashrc
RUN nvm install lts/hydrogen
# RUN apt-get -y install nodejs npm

COPY dist/app.jar /app.jar

COPY run.sh /run.sh
RUN chmod +x /run.sh

ENTRYPOINT /bin/bash /run.sh