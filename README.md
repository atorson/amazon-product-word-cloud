# Word Cloud Web-service: Amazon Product Descriptions
This simple web service provides a RESTful endpoint that allows to: 
   - /POST: receive Amazon product URLs
   - /GET: provide a Top-K word cloud of all processed product descriptions
   
The service uses the following collection of components:
  - Configuration: simple Typesafe-config structure of application parameters
  - Actor: Akka actor system that powers the app
  - Crawler: allows to retrieve product descriptions from Amazon 
  - Store: allows to cache product URLs and word cloud representations
  - Stream Analytics: provides the data pipeline to incrementally process product descriptions and update the word cloud
  - REST: defines the routes for the web service endpoint

All components are defined by interface: implementations are injected via Cake DI Pattern
  
# REST
  
  RESTful HTTP endpoint is available via root /POST and /GET methods at 8080 port (both host and port can be re-configured in 'wordcloud.conf')
  It is implemented using Akka-HTTP library and is decorated with Swagger (available via /swagger endpoint, by default at the same host and port)
  The /GET method returns a JSON representation of the word cloud.
  
#Crawler

There are two options for the Crawler module: Scraper and AWS

By default, Scraper crawler is used (mostly, because no AWS credentials are needed). AWS crawler is better (though requires AWS credentials) - and can be enabled on application start-up by passing optional -AWS command line parameter

Scraper crawler (based on scala-scraper library) imitates a browser (JSoup implementation, no JavaScript invokations) which uses Amazon HTML endpoint and CSS query to extract product description data

AWS crawler uses official Amazon Commercial API service and uses AWS XML endpoint requests: product description is extracted using XML DOM parser 

Important: AWS Commercial API web service authenticates requests signed with a secure hash of (typically, current) timestamp signed with a private key associated with AWS developer account
The 'aws' sub-config in 'wordcloud.conf' has three values that MUST be overriden if AWS Crawler version is meant to be used(there are 'XXX' values set by default in there) to avoid BAD responses from AWS in runtime:
   - AWS Associate Tag (account nick name)
   - AWS Access Key ID (id for your AWS security key - so that AWS can look up its part of the secret key)
   - AWS Secret Key (this is the most sensitive: your part of the AWS secret key)
Note: AWS allows to create many keys (two key pairs for free) and deactivate them at any time   

Important: Scraper can miss some product descriptions that are otherwise available via AWS(e.g. B00TSUGXKE which is Amazon Fire that does not display distinct 'Product Description' via HTML)

Important: Both crawler implementations define a 'requestInterval' parameter meaning the average interval of the OK response of the underlying Amazon service 
Scraper has it set intentionally low at 100 millis (Amazon HTML is not giving 503) even though Amazon typically responds much slower (in my case it was 1500 millis on average)
AWS has it set at 1000 millis (AWS really throttles its service with 503 responses, at least, for free developer accounts) 

REST module is managing the throttling the flow of AWS requests for new product URLs using an internal request buffer (capacity proportional to requestInterval).
This throttling can really constrain the /POST API load when the product cache is cold - but should not be a problem once it is warm and URLs start to repeat themselves. 
The app does not re-query AWS for duplicate URLs and responds immediately in such cases (with 200 code instead of 201)

As a workaround - it is possible to re-configure the requestInterval (requires code rebuild) rate to be much higher - and deal with 503 responses by retrying the failed URLs later.
This is not implemented in the app - but can be achieved via endlessly repeating /POST requests for the same URLs (say, every few secs)

# Store and Stream analytics

There are two implemented versions of the service: Local and Distributed. 

By default, application starts in Local mode. Distributed mode can be enabled at start up by passing optional -BFG command line parameter    

Local is not clustered, not persistent and has no retrying - so can not be considered truly reliable.

Local is scalable to the extent of any reasonable language size (a few millions of words) and medium product spaces (up to a billion, limited by a single JVM heap size)

Distributed is clustered, persistent and reliable. It is of Big Data caliber: limited by cluster size. 
Note: Distributed is not using any probabilistic data structures (like Count-Min Sketch) to handle really extreme data set sizes - though it is not difficult to add it.

Local version: 

  - does not require any external services running, other than Amazon: just launch this app and start using the RESTful service defined by it
  - very fast responses for both GET and POST (no network hop to request external cache services)
  - uses Local store module implementation: 
       a) all product IDs (extracted from URLs) are stored in a local in-memory cache backed by an immutable HashMap[Key, Unit]
       b) word cloud is stored in a local in-memory cache backed up by immutable HashMap[Word = String, WordCount = Long] and immutable TreeMap[(Word, WordCount), Unit] combo
       c) all cache operations are atomic (hidden behind an Akka Actor) and scalable (due to their incremental nature tailored for streaming updates).
       d) TreeMap (backed by an RB-tree) arguably provides the best time performance trade off for this application 
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
      b) Spark Streams job is reading from Kafka and updating word cloud results in Redis
      c) simple EnglishWordTokenizer is used for Tokenization (instead of MLib)
      d) simple countByValue() is used (instead of complex probabilistic structures like Count-Min Sketch)
      e) does not use Spark accumulators to handle county increments: Redis SortedSet zincrby() is handling that
      f) Spark job can be configured(via 'wordcloud.conf') to periodically save its state into a checkpoint (not enabled/needed by default)
      
        
#Launching
        
        $ sbt assembly
        
        It produces a fat jar in the /target/scala-x.xx folder.  Launch this jar using 'java -jar the-jar-name.jar' command with optional -BFG parameter for Distributed mode and optional -AWS parameter for AWS Crawler

#Dependencies
       
        Local mode does not require any additional external services (other than Amazon) running: it has all its dependencies (Akka 2.4, Swagger-Akka-Http 0.6 and scala-scraper 1.2) embedded
        
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

