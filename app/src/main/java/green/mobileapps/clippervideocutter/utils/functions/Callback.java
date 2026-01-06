package green.mobileapps.clippervideocutter.utils.functions;

public interface Callback<T, V> {
  void onSuccess(T t);
  void onFailure(V v);
}
