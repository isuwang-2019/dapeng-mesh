FROM dapengsoa/openjdk:java8-with-arthas

RUN mkdir /dapeng-mesh && mkdir /dapeng-mesh/logs
COPY  ./startup.sh  /dapeng-mesh/
RUN chmod 755 /dapeng-mesh/startup.sh
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8
ENV TZ CST-8

WORKDIR /dapeng-mesh

COPY ./apps/dapeng-mesh-jar-with-dependencies.jar /dapeng-mesh/
COPY ./service-whitelist.xml /dapeng-mesh/


ENTRYPOINT exec  /dapeng-mesh/startup.sh

