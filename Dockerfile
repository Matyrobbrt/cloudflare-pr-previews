# Container image that runs your code
FROM ubuntu
RUN apt install openjdk-17-jre
RUN apt install nodejs npm

COPY dist/app.jar /app.jar

COPY run.sh /run.sh
RUN chmod +x /run.sh

ENTRYPOINT /bin/bash /run.sh