(ns instaparse.abnf
  "This is the context free grammar that recognizes ABNF notation."
  (:require [instaparse.core :as insta]
            [instaparse.cfg :as cfg]
            [instaparse.reduction :as red])
  (:use instaparse.combinators-source))

(def abnf-core
  {:ALPHA (regexp "[a-zA-Z]")
   :BIT (regexp "[01]")
   :CHAR (regexp "[\\u0001-\\u007F]")
   :CR (string "\\u000D")
   :CRLF (string "\\u000D\\u000A")
   :CTL (regexp "[\\u0000-\\u001F|\\u007F]")
   :DIGIT (regexp "[0-9]")
   :DQUOTE (string "\\u0022")
   :HEXDIG (regexp "[0-9A-F]")
   :HTAB (string "\\u0009")
   :LF (string "\\u000A")
   :LWSP (alt (nt :WSP)
              (star
                (cat (nt :CRLF)
                     (nt :WSP))))
   :OCTET (regexp "[\\u0000-\\u00FF]")
   :SP (string "\\u0020")
   :VCHAR (regexp "[\\u0021-\\u007E]")
   :WSP (alt (nt :SP) (nt :HTAB))})
            
(def abnf-grammar
  "
<rulelist> = <opt-whitespace> (rule | hide-tag-rule)+;
rule = rulename-left <defined-as> alternation <opt-whitespace>;
hide-tag-rule = hide-tag <defined-as> alternation <opt-whitespace>;
rulename-left = rulename;
rulename-right = rulename;
<rulename> = #'[a-zA-Z][-a-zA-Z0-9]*(?x) #identifier';
<hide-tag> = <'<' opt-whitespace> rulename-left <opt-whitespace '>'>;
defined-as = <opt-whitespace> ('=' | '=/') <opt-whitespace>;
alternation = concatenation (<opt-whitespace '/' opt-whitespace> concatenation)*;
concatenation = repetition (<whitespace> repetition)*;
repetition = [repeat] <opt-whitespace> element;
repeat = NUM | (NUM? '*' NUM?);
<element> = rulename-right | group | hide | option | char-val | num-val;
<group> = <'(' opt-whitespace> alternation <opt-whitespace ')'>;
option = <'[' opt-whitespace> alternation <opt-whitespace ']'>;
hide = <'<' opt-whitespace> alternation <opt-whitespace '>'>;
char-val = char-val-dq | char-val-sq
<char-val-dq> = <DQUOTE> #'[\\u0020-\\u0021\\u0023-\\u007E]'* <DQUOTE>;
<char-val-sq> = <SQUOTE> #'[\\u0020-\\u0026\u0028-\u007E]'* <SQUOTE>;
<num-val> = <'%'> (bin-val | dec-val | hex-val);
bin-val = <'b'> bin-char
          [ (<'.'> bin-char)+ | ('-' bin-char) ];
bin-char = BIT+;
dec-val = <'d'> dec-char
          [ (<'.'> dec-char)+ | ('-' dec-char) ];
dec-char = DIGIT+;
hex-val = <'x'> hex-char
          [ (<'.'> hex-char)+ | ('-' hex-char) ];
hex-char = HEXDIG+;
NUM = DIGIT+;
<BIT> = '0' | '1';
<DIGIT> = #'[0-9]';
<DQUOTE> = '\\u0022';
<SQUOTE> = '\\u0027';
<HEXDIG> = #'[0-9A-Fa-f]';
opt-whitespace = #'\\s*(?:;.*?\\u000D?\\u000A\\s*)*(?x) # optional whitespace or comments';
whitespace = #'\\s+(?:;.*?\\u000D?\\u000A\\s*)*(?x) # whitespace or comments';
")

(defn char-range
  "Takes two chars and returns a combinator representing a range of characters."
  [char1 char2]
  (regexp (str "[" char1 "-" char2 "]")))

(defn get-char-combinator
  ([num1]
    (string (str (char num1))))
  ([num1 num2 & nums]
    (let [v (vec (concat [num1 num2] nums))]
      (if (= (v 1) "-")
        (char-range (char (v 0))
                    (char (v 2)))
        (apply alt (for [n v]
                     (string (str (char n)))))))))

(defn project
  "Restricts map to certain keys"
  [m ks]
  (into {}
        (for [k ks
              :when (contains? m k)]
          [k (m k)])))
          
(defn merge-core
  "Merges abnf-core map in with parsed grammar map"
  [grammar-map]
  (merge
    (project abnf-core (distinct (mapcat cfg/seq-nt (vals grammar-map))))
    grammar-map))

(defn hide-tag?
  "Tests whether parser was constructed with hide-tag"
  [p]
  (= (:red p) red/raw-non-terminal-reduction))

(defn alt-preserving-hide-tag [p1 p2]
  (let [hide-tag-p1? (hide-tag? p1)
        hide-tag-p2? (hide-tag? p2)]
    (cond
      (and hide-tag-p1? hide-tag-p2?)
      (hide-tag (alt (dissoc p1 :red) (dissoc p2 :red)))
      hide-tag-p1?
      (hide-tag (alt (dissoc p1 :red) p2))
      hide-tag-p2?
      (hide-tag (alt p1 (dissoc p2 :red)))
      :else
      (alt p1 p2))))
        
(def abnf-transformer
  {   
   :rule hash-map
   :hide-tag-rule (fn [tag rule] {tag (hide-tag rule)})
   :rulename-left #(keyword (clojure.string/upper-case (apply str %&)))
   :rulename-right #(nt (keyword (clojure.string/upper-case (apply str %&))))
   ; since rulenames are case insensitive, convert it to upper case internally to be consistent
   :alternation alt
   :concatenation cat
   :repeat (fn ([num1 _ num2] {:low num1, :high num2})
             ([item1 item2] (if (= item1 "*")
                              {:high item2}
                              {:low item1}))
             ([_] {}))
                 
   :repetition (fn 
                 ([repeat element]
                   (cond
                     (empty? repeat) (star element)
                     (= (count repeat) 2) (rep (:low repeat) (:high repeat) element)
                     (= (:low repeat) 1) (plus element)
                     (= (:high repeat) 1) (opt element)
                     :else (rep (or (:low repeat) 0)
                                (or (:high repeat) Double/POSITIVE_INFINITY)
                                element)))
                 ([element]
                   element))
   :option opt
   :hide hide
   :char-val (fn [& cs]
               ; case insensitive string
               (string-ci (apply str cs)))
   :bin-char (fn [& cs]
               (Integer/parseInt (apply str cs) 2))
   :dec-char (fn [& cs]
               (Integer/parseInt (apply str cs)))
   :hex-char (fn [& cs]
               (Integer/parseInt (apply str cs) 16))
   :bin-val get-char-combinator
   :dec-val get-char-combinator
   :hex-val get-char-combinator
   :NUM #(Integer/parseInt (apply str %&))})

(def abnf-parser (insta/parser abnf-grammar))

(defn rules->grammar-map
  [rules]
  (merge-core (apply merge-with alt-preserving-hide-tag rules)))

(defn make-abnf-parser
  [s]
  (let [parsed-spec (insta/parse abnf-parser s)
        rules (insta/transform abnf-transformer parsed-spec)]        
    (-> (rules->grammar-map rules)
      (insta/parser :start (key (first (first rules)))))))

(defn abnf
  "Takes an ABNF grammar specification string and returns the combinator version.
If you give it the right-hand side of a rule, it will return the combinator equivalent.
If you give it a series of rules, it will give you back a grammar map.   
Useful for combining with other combinators."
  [spec]
  (if (re-find #"=" spec)
    (->> (insta/parse abnf-parser spec)
      (insta/transform abnf-transformer)
      rules->grammar-map)
    (->> (insta/parse abnf-parser spec :start :alternation)
      (insta/transform abnf-transformer))))