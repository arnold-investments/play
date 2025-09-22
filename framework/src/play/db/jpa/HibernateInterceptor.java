package play.db.jpa;


import org.hibernate.Interceptor;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.type.Type;

import java.io.Serializable;


public class HibernateInterceptor implements Interceptor, Serializable {

	public HibernateInterceptor() {

	}

	public boolean onLoad(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
		return false;
	}

	public boolean onFlushDirty(Object entity, Object id, Object[] currentState, Object[] previousState, String[] propertyNames, Type[] types) {
		return false;
	}

	public void onDelete(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
	}

	public Object getEntity(String entityName, Object id) {
		return null;
	}
  
	@Override
	public int[] findDirty(Object entity, Object id, Object[] currentState, Object[] previousState, String[] propertyNames, Type[] types) {
		if (entity instanceof JPABase && !((JPABase) entity).willBeSaved) {
			return new int[0];
		}
		return null;
	}

    @Override
    public boolean onCollectionUpdate(Object collection, Object key) {
        if (collection instanceof PersistentCollection) {
            Object o = ((PersistentCollection<?>) collection).getOwner();
            if (o instanceof JPABase) {
                if (entities.get() != null) {
                    return ((JPABase) o).willBeSaved || ((JPABase) entities.get()).willBeSaved;
                } else {
                    return ((JPABase) o).willBeSaved;
                }
            }
        } else {
            System.out.println("HOO: Case not handled !!!");
        }
        return Interceptor.super.onCollectionUpdate(collection, key);
    }

    @Override
    public boolean onCollectionRecreate(Object collection, Object key) {
        if (collection instanceof PersistentCollection) {
            Object o = ((PersistentCollection<?>) collection).getOwner();
            if (o instanceof JPABase) {
                if (entities.get() != null) {
                    return ((JPABase) o).willBeSaved || ((JPABase) entities.get()).willBeSaved;
                } else {
                    return ((JPABase) o).willBeSaved;
                }
            }
        } else {
            System.out.println("HOO: Case not handled !!!");
        }

        return Interceptor.super.onCollectionRecreate(collection, key);
    }

    @Override
    public boolean onCollectionRemove(Object collection, Object key)  {
        if (collection instanceof PersistentCollection) {
            Object o = ((PersistentCollection<?>) collection).getOwner();
            if (o instanceof JPABase) {
                if (entities.get() != null) {
                    return ((JPABase) o).willBeSaved || ((JPABase) entities.get()).willBeSaved;
                } else {
                    return ((JPABase) o).willBeSaved;
                }
            }
        } else {
            System.out.println("HOO: Case not handled !!!");
        }
        return Interceptor.super.onCollectionRemove(collection, key);
    }

    protected final ThreadLocal<Object> entities = new ThreadLocal<>();

    @Override
    public boolean onSave(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        entities.set(entity);
        return Interceptor.super.onSave(entity, id, state, propertyNames, types);
    }

    @Override
    public void afterTransactionCompletion(org.hibernate.Transaction tx) {
        entities.remove();
    }
}