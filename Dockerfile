# Use the official Clojure image as the base image
FROM clojure:openjdk-11-lein

# Set the working directory
WORKDIR /usr/src/app

# Copy project.clj into the container to install dependencies
COPY project.clj .

# Install dependencies
RUN lein deps

# Copy the rest of the application code into the container
COPY . .

# Expose the port your application will run on
EXPOSE 3000

# Run the application using Leiningen
CMD ["lein", "ring", "server-headless"]