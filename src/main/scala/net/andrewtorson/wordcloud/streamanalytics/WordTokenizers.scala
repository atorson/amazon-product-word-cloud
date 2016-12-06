package net.andrewtorson.wordcloud.streamanalytics

import edu.stanford.nlp.process.Stemmer

/**
 * Created by Andrew Torson on 12/1/16.
 */

/**
 * Simple English word tokenizer
 * Backed by Regex, hard-coded Stop-Word list and Stanford NLP Stemmer
 */
object EnglishRegexTokenizer extends WordsTokenizer{
  override def tokenize(in: String): Seq[String] = {

   val stopWords = Seq("a", "about", "above", "above", "across", "after", "afterwards", "again", "against", "all", "almost", "alone", "along", "already",
     "also","although","always","am","among", "amongst", "amoungst", "amount",  "an", "and", "another", "any","anyhow","anyone","anything","anyway", "anywhere",
     "are", "arent", "around", "as",  "at", "back","be","became", "because","become","becomes", "becoming", "been", "before", "beforehand", "behind", "being", "below",
     "beside", "besides", "between", "beyond", "bill", "both", "bottom","but", "by", "call", "can", "cannot", "cant", "co", "con", "could", "couldnt", "cry",
     "de", "describe", "detail", "did", "didnt", "do", "doing", "done", "down", "due", "during", "each", "eg", "eight", "either", "eleven","else", "elsewhere", "empty", "enough", "etc",
     "even", "ever", "every", "everyone", "everything", "everywhere", "except", "few", "fifteen", "fify", "fill", "find", "fire", "first", "five", "for", "former",
     "formerly", "forty", "found", "four", "from", "front", "full", "further", "get", "give", "go", "had", "has", "hasnt", "have", "he", "hence", "her", "here",
     "hereafter", "hereby", "herein", "hereupon", "hers", "herself", "him", "himself", "his", "how", "however", "hundred", "ie", "if", "in", "inc", "indeed",
     "interest", "into", "is", "isnt", "it", "its", "itself", "keep", "last", "latter", "latterly", "least", "less", "ltd", "made", "many", "may", "me", "meanwhile",
     "might", "mill", "mine", "more", "moreover", "most", "mostly", "move", "much", "must", "my", "myself", "name", "namely", "neither", "never", "nevertheless",
     "next", "nine", "no", "nobody", "none", "noone", "nor", "not", "nothing", "now", "nowhere", "of", "off", "often", "on", "once", "one", "only", "onto", "or",
     "other", "others", "otherwise", "our", "ours", "ourselves", "out", "over", "own","part", "per", "perhaps", "please", "put", "rather", "re", "same", "see",
     "seem", "seemed", "seeming", "seems", "serious", "several", "she", "should", "show", "side", "since", "sincere", "six", "sixty", "so", "some", "somehow",
     "someone", "something", "sometime", "sometimes", "somewhere", "still", "such", "system", "take", "ten", "than", "that", "the", "their", "them", "themselves",
     "then", "thence", "there", "thereafter", "thereby", "therefore", "therein", "thereupon", "these", "they", "thickv", "thin", "third", "this", "those", "though",
     "three", "through", "throughout", "thru", "thus", "to", "together", "too", "top", "toward", "towards", "twelve", "twenty", "two", "un", "under", "until", "up",
     "upon", "us", "very", "via", "was", "we", "well", "were", "what", "whatever", "when", "whence", "whenever", "where", "whereafter", "whereas", "whereby",
     "wherein", "whereupon", "wherever", "whether", "which", "while", "whither", "who", "whoever", "whole", "whom", "whose", "why", "will",
     "with", "within", "without", "would", "yet", "you", "your", "yours", "yourself", "yourselves", "the") ++
     Seq("a","about","above","after","again","against","all","am","an","and","any","are","arent","as","at","be","because","been","before",
       "being","below","between","both","but","by","cant","cannot","could","couldnt","did","didnt","do","does","doesnt","doing","dont","down",
       "during","each","few","for","from","further","had","hadnt","has","hasnt","have","havent","having","he","hed","hell","hes","her","here","heres",
       "hers","herself","him","himself","his","how","hows","i","id","ill","im","ive","if","in","into","is","isnt","it","its","its","itself","lets","me",
       "more","most","mustnt","my","myself","no","nor","not","of","off","on","once","only","or","other","ought","our","ours","ourselves","out","over",
       "own","same","shant","she","shed","shell","shes","should","shouldnt","so","some","such","than","that","thats","the","their","theirs","them",
       "themselves","then","there","theres","these","they","theyd","theyll","theyre","theyve","this","those","through","to","too","under","until","up",
       "very","was","wasnt","we","wed","well","were","weve","were","werent","what","whats","when","whens","where","wheres","which","while","who","whos","whom",
       "why","whys","with","wont","would","wouldnt","you","youd","youll","youre","youve","your","yours","yourself","yourselves")

    val stemmer = new Stemmer()

    in
    //remove HTML
       .replaceAll("<[^>]*>", "")
      //replace internal hyhphens colons pluses etc with whitespace
      .replaceAll("[-+:;,.]", " ")
      // remove all non-characters
      .replaceAll("[^a-zA-Z ]", "")
      // lower case and split on white space
      .toLowerCase().split("\\s+")
      // filter onechar words
      .filter(_.size > 1)
      // filter stop words
      .filterNot(stopWords contains _)
      // stemmer
      .map(stemmer.stem(_))
  }    
    
  
    
  }
