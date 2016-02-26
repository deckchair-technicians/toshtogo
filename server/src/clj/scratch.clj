(ns scratch)

(defn lines [[x y]]
  [[[x y] [(+ x 2) y]]
   [[x y] [(+ x 2) (+ y 2)]]
   [[x y] [x (+ y 2)]]])

(defn in-bounds? [grid [x y]]
  (and (>= x 0)
       (>= y 0)
       (< y (count grid))
       (< x (count (first grid)))))

(defn all-points [grid]
  (for [x (range 0 (count (first grid)))
        y (range 0 (count grid))]
    [x y]))

(defn value [grid [x y]]
  (-> (nth grid y)
      (nth x)))

(defn points-on-line [[[x1 y1] [x2 y2]]]
  (let [length (Math/abs (- x2 x1))
        height (Math/abs (- y2 y1))]
    (cond (= 0 length) [[x1 y1] [x2 (+ 1 y1)][x2 y2]]
          (= 0 height) [[x1 y1] [(+ 1 x1) y2][x2 y2]]
          true [[x1 y1] [(+ 1 x1) (+ 1 y1)][x2 y2]])))

(def g [[5 4 1 6 2 9 1]
        [2 9 2 5 7 1 5]
        [6 8 5 3 6 7 2]
        [7 1 5 8 3 8 4]
        [9 4 2 3 7 4 5]
        [4 8 6 9 3 9 1]
        [7 3 8 6 2 8 3]])

(defn matches [number [x y z]]
  (->> [(+ (* x y) z)
        (- (* x y) z)
        (+ (* z y) x)
        (- (* z y) x)]
       (some #(= number %))))

(defn find-solutions [grid pred]
  (->> grid
       (all-points)
       (mapcat lines)
       (filter (fn [[from to]]
                 (and (in-bounds? grid from)
                      (in-bounds? grid to))))
       (filter (fn [line]
                 (pred (map (partial value grid) (points-on-line line)))))))

(clojure.pprint/pprint (find-solutions g (partial matches 48)))
