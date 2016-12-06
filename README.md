# Word Cloud Web-service: Amazon Product Descriptions
This simple web service provides a RESTful endpoint that allows to: 
   - /POST: receive Amazon product URLs
   - /GET: provide a Top-K word cloud of all processed product descriptions
   
The service uses the following collection of components:
  - Configuration: simple Typesafe-config structure of application parameters
  - Actor: Akka actor system that powers the app
  - AWS: allows to retrieve product descriptions from Amazon AWS
  - Store: allows to cache product URLs and word cloud representations
  - Stream Analytics: provides the data pipeline to incrementally process product descriptions and update the word cloud
  - REST: defines the routes for the web service endpoint

All components are defined by interface: implementations are injected via Cake DI Pattern
  
There are two implemented versions of the service: Local and Distributed. 

Local is not clustered, not persistent and has no retrying - so can not be considered truly reliable.

Local is scalable to the extent of any reasonable language size (a few millions of words) and medium product spaces (up to a billion, limited by a single JVM heap size)

Distributed is clustered, persistent and reliable. It is of Big Data caliber: limited by cluster size. 
Note: Distributed is not using any probabilistic data structures (like Count-Min Sketch) to handle really extreme data set sizes - though it is not difficult to add it.

Important: Both versions are throttled by AWS request rate (which is set in 'aws' sub-config, default = 1req/sec) to avoid being hit with 503 responses from Amazon

REST module is managing the throttling the flow of AWS requests for new product URLs 
This can really constrain the /POST API load when the product cache is cold - but should not be a problem once it is warm and URLs start to repeat themselves. 
The app does not re-query AWS for duplicate URLs and responds immediately in such cases (with 200 code instead of 201)
As a workaround - it is possible to re-configure the AWS request rate to be much higher - and deal with 503 responses by retrying the failed URLs later.
This is not implemented in the app - but can be easily achieved via endlessly repeating /POST requests for the same URLs (say, every few secs)

Note: AWS module implementation uses official Amazon Commercial API and thus is throttled by Amazon. 
It is easy to inject a different implementation (not provided in this app) that imitates Amazon HTML endpoint browser requests and may not be throttled so heavily. 
However, it is a questionable practice (may be OK for a demo though)
 
Important: AWS Commercial API web service authenticates requests signed with a secure hash of (typically, current) timestamp signed with a private key associated with AWS developer account

The 'aws' sub-config has three values that MUST be overriden (there are 'XXX' values in there) to avoid BAD responses from AWS in runtime:
   - AWS Associate Tag (account nick name)
   - AWS Access Key ID (id for your AWS security key - so that AWS can look up its part of the secret key)
   - AWS Secret Key (this is the most sensitive: your part of the AWS secret key)
   
Note: AWS allows to create many keys (two key pairs for free) and deactivate them at any time   

By default, application starts in Local version: this can be changed by starting it with the optional -BFG command line parameter   

Local version: 

  - does not require any external services running, other than Amazon: just launch this app and start using the RESTful service defined by it
  - very fast responses for both GET and POST 
  - uses Local store module implementation: 
       a) all product IDs (extracted from URLs) are stored in a local in-memory cache backed by an immutable HashMap[Key, Unit]
       b) word cloud is stored in a local in-memory cache backed up by immutable HashMap[Word = String, WordCount = Long] and immutable TreeMap[(Word, WordCount), Unit] combo
       c) all cache operations are atomic (hidden behind an Akka Actor) and scalable (due to their incremental nature tailored for streaming updates).
       TreeMap provides very fast sorted iterator for Reads (immutable RB-tree in-order traversal) while being somewhat slow (logN) with Writes 
  - uses Akka Streams stream analytics module implementation:
       a) allows to batch new product descriptions over a given (configurable) time window to optimize the TreeMap sorting frequency (default window is 1000 millisec)
       b) uses Akka Stream flow with Publisher Actor source and Foreach sink (that updates the word cloud cache) to pipeline the word cloud data processing logic
       c) uses simple EnglishWordTokenizer which is based on regex, hard-coded stop words and Stanford NLP stemmer
  - the local Akka flow that processes the word cloud flow - is micro-batched (configurable via 'wordcloud' sub-config, default is 1sec) to manage the CPU load (important when data grows large)

Distributed version:

  - uses Distributed store module implementation:
      a) all product IDs and word cloud versions are stored in Redis cache (it has very suitable structures for both)
      b) new product IDs to be processed are sent to a Kafka queue
  - uses Spark Streams streams analytics module implementation:
      a) micro-batched (configurable via 'wordcloud' sub-config, default is 1sec) naturally by Spark Streaming
      b) Spark Streams job is reading from Kafka, periodically saving its state by key in a checkpoint and updating word cloud results in Redis 
      c) simple EnglishWordTokenizer is used for Tokenization (instead of MLib)
      d) simple countByValue() is used (instead of complex probabilistic structures like Count-Min Sketch)
      e) does not use Spark accumulators to handle county increments: Redis SortedSet zincrby() is handling that
      
        
#Launching
        
        $ sbt assembly
        
        It produces a fat jar in the /target/scala-x.xx folder.  Launch this jar using 'java -jar the-jar-name.jar' command with optional -BFG parameter for Distributed mode

#Dependencies
       
        Local mode does not require any additional external services (other than Amazon) running: it has all its dependencies (Akka 2.4 and Swagger-Akka-Http 0.6) embedded
        
        Distributed mode has extra dependencies embedded: 
           - Akka-Stream-Kafka 0.13
           - Redis-React 0.8
           - Spark 2.0.1
           - Spark-Stream-Kafka-0-10 2.01
        Most important, the Distributed mode has extra of external service dependencies that must be up and running and available for client connections
        See the wordcloud.conf for the external service endpoints (all are set at vendor defaults)
         
           - Redis: distributed cache service. The embedded client version (Redis-React library) needs 2.8.x server versions
             Follow this link to install(on Ubuntu): https://redis.io/topics/quickstart
             
           - Kafka: distributed pub/sub messaging queue. The embedded client version (Akka-Kafka) needs 0.10.x server versoions
             Follow this link to install(on Ubuntu): https://devops.profitbricks.com/tutorials/install-and-configure-apache-kafka-on-ubuntu-1604-1/
         
#Using HTTP

    	- /POST: uses a single query parameter 'ProductURL' defining a given Amazon product web page (as encoded URL string). 
    	Returns text/plain reponse of HTTP status code + service message
    
    	Give it a try via CURL: curl -X POST "localhost:8080?ProductURL=http%3A%2F%2Fwww.amazon.com%2Fgp%2Fproduct%2FB00SMBFZNG"
        or use the simulateRequests.sh script provided in the root project folder:  ./simulateRequests.sh localhost 8080 ProductURL
    	
    	- /GET: uses a single query parameter 'TopK' defining the non-negative integer value of the desired word cloud size. 
    	Returns application/json response (Spray-JSON protocol for Tuple2 case class) with the requested TopK integer value and the WordCloud as Seq[(String, Int)]
    	
    	Give it a try via CURL: curl -X GET "localhost:8080?TopK=25"
    	
or just use Swagger UI(bundled with this app):

     	http://localhost:8080/swagger/index.html

